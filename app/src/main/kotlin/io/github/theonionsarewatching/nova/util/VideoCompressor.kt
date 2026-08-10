package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Video compression engine: shrinks a video to fit the carrier's MMS byte
 * limit. MediaCodec surface-to-surface transcode — the decoder renders
 * straight onto the encoder's input surface (the buffer queue scales to the
 * encoder's size), H.264 at a bitrate computed from the target size and the
 * clip's duration. AAC audio is passed through untouched; other audio codecs
 * are dropped (logged) rather than transcoded.
 *
 * Every step logs under "video-compress". ANY failure returns null and the
 * caller proceeds exactly as before — the engine can only improve outcomes,
 * never break a send that used to work.
 */
object VideoCompressor {

    private const val MIN_VIDEO_BPS_FLOOR = 28_000
    private const val AUDIO_TRANSCODE_BPS = 24_000
    private const val ABSOLUTE_MAX_SEC = 300L  // user-set ceiling: 5 minutes

    // resolution ladder: the bitrate the clip's length leaves us picks the
    // size — long clips walk down to feature-phone territory rather than
    // being refused (carriers limit BYTES, not duration; there is no
    // duration key anywhere in the platform's MMS carrier config).
    // Consts DECLARED FIRST: a Kotlin property initializer cannot forward-
    // reference a const below it in the same object (CI, v0.9.113).
    private val TIERS = listOf(
        250_000 to 640, 120_000 to 480, 60_000 to 320, MIN_VIDEO_BPS_FLOOR to 192
    )

    /** One source of truth for what a video must fit into: the SENDER's real
     *  enforcement cap (carrier max minus its header margin) minus room for
     *  text and MMS overhead. The 08-09 field failure was two DIFFERENT
     *  limits: the compressor hit its target and the sender refused anyway. */
    fun targetBytes(context: Context): Long =
        (CarrierMms.limits(context).maxBytes - 30 * 1024 - 20 * 1024).toLong()

    /** Longest clip that can fit [target] at the minimum acceptable bitrate —
     *  the practical "carrier max video length" (user question): beyond this
     *  no amount of compression helps, so we refuse up front with a clear
     *  message instead of transcoding to mush. */
    fun maxDurationSec(target: Long): Long =
        (target * 8 / (MIN_VIDEO_BPS_FLOOR + AUDIO_TRANSCODE_BPS))
            .coerceAtMost(ABSOLUTE_MAX_SEC)

    /** The limit for THIS phone and THIS clip, nothing guessed: the byte cap
     *  comes from the device's own carrier MMS config, the audio share from
     *  the clip's real AAC bitrate (or zero when audio would be dropped). */
    fun maxDurationSecFor(context: Context, src: File): Long {
        // audio no longer bounds the length: when passthrough doesn't fit
        // the budget the track is transcoded down to AUDIO_TRANSCODE_BPS,
        // so the floor math is the same for every clip
        return maxDurationSec(targetBytes(context))
    }

    /** Decode the source audio track to PCM and re-encode as low-bitrate
     *  AAC-LC. Returns the encoder's output format plus every encoded sample
     *  buffered in memory (a 3-minute track at 24 kbps is ~540 KB), or null
     *  on any failure — the caller drops audio rather than failing the send.
     *  Buffering first is what lets the muxer receive the REAL encoder
     *  format before it starts. */
    private fun transcodeAudio(
        extractor: MediaExtractor, track: Int, srcFormat: MediaFormat
    ): Pair<MediaFormat, List<Pair<ByteArray, MediaCodec.BufferInfo>>>? {
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        try {
            val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)!!
            val rate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(track)
            dec = MediaCodec.createDecoderByType(srcMime)
            dec.configure(srcFormat, null, null, 0)
            dec.start()
            val encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_TRANSCODE_BPS)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
            var outFormat: MediaFormat? = null
            val dInfo = MediaCodec.BufferInfo()
            val eInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var eosQueued = false
            // PCM handoff with chunking: a decoder output can exceed one
            // encoder input buffer
            var pcm: ByteArray? = null
            var pcmOff = 0
            var pcmPtsUs = 0L
            val bytesPerUs = rate.toLong() * ch * 2 / 1_000_000.0
            val deadline = System.currentTimeMillis() + 90_000
            while (!encoderDone) {
                if (System.currentTimeMillis() > deadline)
                    throw IllegalStateException("audio transcode timeout")
                if (!extractorDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(b, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decoderDone && pcm == null) {
                    val o = dec.dequeueOutputBuffer(dInfo, 10_000)
                    if (o >= 0) {
                        if (dInfo.size > 0) {
                            val b = dec.getOutputBuffer(o)!!
                            val arr = ByteArray(dInfo.size)
                            b.position(dInfo.offset); b.get(arr)
                            pcm = arr; pcmOff = 0; pcmPtsUs = dInfo.presentationTimeUs
                        }
                        val eos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        dec.releaseOutputBuffer(o, false)
                        if (eos) decoderDone = true
                    }
                }
                val chunk = pcm
                if (chunk != null || (decoderDone && !eosQueued)) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (chunk != null) {
                            val b = enc.getInputBuffer(i)!!
                            val n = minOf(b.capacity(), chunk.size - pcmOff)
                            b.put(chunk, pcmOff, n)
                            val pts = pcmPtsUs + (pcmOff / bytesPerUs).toLong()
                            enc.queueInputBuffer(i, 0, n, pts, 0)
                            pcmOff += n
                            if (pcmOff >= chunk.size) pcm = null
                        } else {
                            enc.queueInputBuffer(i, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(eInfo, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = enc.outputFormat
                    o >= 0 -> {
                        if (eInfo.size > 0 &&
                            eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val b = enc.getOutputBuffer(o)!!
                            val arr = ByteArray(eInfo.size)
                            b.position(eInfo.offset); b.get(arr)
                            val meta = MediaCodec.BufferInfo()
                            meta.set(0, arr.size, eInfo.presentationTimeUs, eInfo.flags)
                            samples.add(arr to meta)
                        }
                        val eos = eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(o, false)
                        if (eos) encoderDone = true
                    }
                }
            }
            val f = outFormat ?: return null
            extractor.unselectTrack(track)
            return f to samples
        } catch (e: Exception) {
            return null
        } finally {
            try { dec?.stop(); dec?.release() } catch (_: Exception) {}
            try { enc?.stop(); enc?.release() } catch (_: Exception) {}
        }
    }

    /** Stream-copy trim (no re-encode): samples in [startMs, startMs+durMs]
     *  from every track, start aligned to the previous sync frame so the clip
     *  begins on a decodable frame. Returns null on any failure. */
    fun trim(context: Context, src: File, startMs: Long, durMs: Long): File? {
        val out = File(context.cacheDir, "vt_${System.currentTimeMillis()}.mp4")
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(src.absolutePath) }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(src.absolutePath)
                val rot = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                r.release()
                if (rot != 0) muxer.setOrientationHint(rot)
            } catch (_: Exception) {}
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    trackMap[i] = muxer.addTrack(f)
                    extractor.selectTrack(i)
                }
            }
            if (trackMap.isEmpty()) return null
            muxer.start()
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = (startMs + durMs) * 1000
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var baseUs = -1L
            while (true) {
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                val t = extractor.sampleTime
                if (t > endUs) break
                if (baseUs < 0) baseUs = t
                info.set(0, n, t - baseUs,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                trackMap[extractor.sampleTrackIndex]?.let { muxer.writeSampleData(it, buf, info) }
                extractor.advance()
            }
            muxer.stop()
            DiagLog.log(context, "video-compress",
                "trimmed ${src.length() / 1024}KB -> ${out.length() / 1024}KB " +
                    "(start=${startMs / 1000}s dur=${durMs / 1000}s)")
            return if (out.length() > 0) out else { out.delete(); null }
        } catch (e: Exception) {
            DiagLog.log(context, "video-compress", "trim failed: ${e.message}")
            out.delete()
            return null
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /** Returns the compressed file, or null when compression isn't possible
     *  or didn't help. [targetBytes] should be the carrier max minus headroom. */
    fun compress(context: Context, src: File, targetBytes: Long): File? {
        if (!src.exists() || src.length() <= targetBytes) return null
        return compressAttempt(context, src, targetBytes, null)?.let { first ->
            if (first.length() <= targetBytes) first
            else {
                // near miss: one retry with the bitrate scaled by the error
                // (the 08-09 case — 1330 KB vs an 1105 KB target — would have
                // succeeded here instead of being discarded)
                val scale = targetBytes.toDouble() / first.length() * 0.85
                DiagLog.log(context, "video-compress",
                    "over target — one retry at ${(scale * 100).toInt()}% bitrate")
                first.delete()
                compressAttempt(context, src, targetBytes, scale)
                    ?.takeIf { it.length() <= targetBytes }
                    .also { if (it == null) DiagLog.log(context, "video-compress",
                        "retry still over — using original path") }
            }
        }
    }

    private fun compressAttempt(
        context: Context, src: File, targetBytes: Long, bpsScale: Double?
    ): File? {
        val out = File(context.cacheDir, "vc_${System.currentTimeMillis()}.mp4")
        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurfaceGl: net.ypresto.androidtranscoder.engine.InputSurface? = null
        var outputSurfaceGl: net.ypresto.androidtranscoder.engine.OutputSurface? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(src.absolutePath) }
            var videoTrack = -1
            var srcFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i; srcFormat = f; break
                }
            }
            if (videoTrack < 0 || srcFormat == null) {
                DiagLog.log(context, "video-compress", "no video track in ${src.name}")
                return null
            }
            extractor.selectTrack(videoTrack)

            val srcW = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
            // ROTATION (08-09 field report: portrait clip came out landscape
            // with the top cut): phones record a LANDSCAPE buffer plus a
            // rotation flag in the container. We keep encoding in buffer
            // orientation and stamp the same flag onto the output so players
            // rotate it exactly like they rotated the original.
            val rotation = try {
                srcFormat.getInteger(MediaFormat.KEY_ROTATION)
            } catch (_: Exception) {
                try {
                    val r = android.media.MediaMetadataRetriever()
                    r.setDataSource(src.absolutePath)
                    val v = r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                    )?.toIntOrNull() ?: 0
                    r.release(); v
                } catch (_: Exception) { 0 }
            }
            val durationUs = try { srcFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
            if (durationUs <= 0) {
                DiagLog.log(context, "video-compress", "unknown duration — skipping")
                return null
            }
            if (durationUs / 1_000_000 > maxDurationSec(targetBytes)) {
                DiagLog.log(context, "video-compress",
                    "clip ${durationUs / 1_000_000}s exceeds the ~${maxDurationSec(targetBytes)}s " +
                        "that can fit this carrier's MMS cap — refusing up front")
                return null
            }
            // AUDIO BUDGET FIRST: passthrough only when the source AAC fits a
            // quarter of the target; otherwise the track is TRANSCODED down
            // to phone-call-grade AAC — long clips used to be impossible
            // purely because their audio ate the whole byte budget
            var audioBytes = 0L
            var audioFormat: MediaFormat? = null
            var audioSrcTrack = -1
            var audioTranscode = false
            audioExtractor = MediaExtractor().apply { setDataSource(src.absolutePath) }
            for (i in 0 until audioExtractor.trackCount) {
                val f = audioExtractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioSrcTrack = i; audioFormat = f
                    val br = try { f.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { 128_000 }
                    val passBytes = br.toLong() * durationUs / 8_000_000L
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC && passBytes <= targetBytes / 4) {
                        audioBytes = passBytes
                    } else {
                        audioTranscode = true
                        audioBytes = AUDIO_TRANSCODE_BPS.toLong() * durationUs / 8_000_000L
                    }
                    break
                }
            }
            var videoBps = (((targetBytes - audioBytes) * 8 * 1_000_000L / durationUs) * 9 / 10)
                .toInt().coerceAtLeast(MIN_VIDEO_BPS_FLOOR)
            if (bpsScale != null) videoBps = (videoBps * bpsScale).toInt()
                .coerceAtLeast(MIN_VIDEO_BPS_FLOOR)
            // the bitrate picks the resolution tier
            val maxDim = TIERS.first { (bps, _) -> videoBps >= bps }.second
            val scale = (maxDim.toFloat() / maxOf(srcW, srcH)).coerceAtMost(1f)
            val dstW = ((srcW * scale).toInt() / 2) * 2
            val dstH = ((srcH * scale).toInt() / 2) * 2
            DiagLog.log(context, "video-compress",
                "src=${src.length() / 1024}KB ${srcW}x${srcH} dur=${durationUs / 1000000}s -> " +
                    "${dstW}x${dstH} @${videoBps / 1000}kbps audio=" +
                    (if (audioSrcTrack < 0) "none"
                     else if (audioTranscode) "transcode@${AUDIO_TRANSCODE_BPS / 1000}k"
                     else "aac-pass"))

            var audioEncFormat: MediaFormat? = null
            var audioSamples: List<Pair<ByteArray, MediaCodec.BufferInfo>>? = null
            if (audioTranscode && audioSrcTrack >= 0 && audioFormat != null) {
                val res = transcodeAudio(audioExtractor, audioSrcTrack, audioFormat)
                if (res != null) {
                    audioEncFormat = res.first
                    audioSamples = res.second
                } else {
                    DiagLog.log(context, "video-compress",
                        "audio transcode failed — audio dropped")
                    audioSrcTrack = -1
                }
            }

            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dstW, dstH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // EGL render bridge (vendored AOSP/CTS surfaces): decoder frames
            // land on an OES texture and are DRAWN at the encoder's size.
            // The direct surface piping this replaces produced corrupted
            // output (colored lines) in the field — buffer-queue scaling is
            // not a real renderer.
            inputSurfaceGl = net.ypresto.androidtranscoder.engine.InputSurface(
                encoder.createInputSurface())
            inputSurfaceGl!!.makeCurrent()
            encoder.start()
            outputSurfaceGl = net.ypresto.androidtranscoder.engine.OutputSurface()
            decoder = MediaCodec.createDecoderByType(srcFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(srcFormat, outputSurfaceGl!!.surface, null, 0)
            decoder.start()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
                DiagLog.log(context, "video-compress", "orientation hint: $rotation deg")
            }
            var muxVideoTrack = -1
            var muxAudioTrack = -1
            var muxerStarted = false

            val bufInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            val deadline = System.currentTimeMillis() + 120_000  // hard stop: 2 min
            while (!encoderDone) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("transcode timeout")
                // feed the decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // drain the decoder: render to the OES texture, draw it at
                // the encoder's size, stamp the time, push the frame through
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                    if (outIdx >= 0) {
                        val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = bufInfo.size > 0
                        decoder.releaseOutputBuffer(outIdx, render)
                        if (render) {
                            outputSurfaceGl!!.awaitNewImage()
                            outputSurfaceGl!!.drawImage()
                            inputSurfaceGl!!.setPresentationTime(bufInfo.presentationTimeUs * 1000)
                            inputSurfaceGl!!.swapBuffers()
                        }
                        if (eos) { decoderDone = true; encoder.signalEndOfInputStream() }
                    }
                }
                // drain the encoder into the muxer
                val encIdx = encoder.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                        val aFmt = audioEncFormat ?: audioFormat
                        if (audioSrcTrack >= 0 && aFmt != null) {
                            muxAudioTrack = muxer.addTrack(aFmt)
                        }
                        muxer.start(); muxerStarted = true
                    }
                    encIdx >= 0 -> {
                        val buf = encoder.getOutputBuffer(encIdx)!!
                        if (bufInfo.size > 0 && muxerStarted &&
                            bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            muxer.writeSampleData(muxVideoTrack, buf, bufInfo)
                        }
                        val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(encIdx, false)
                        if (eos) encoderDone = true
                    }
                }
            }
            // transcoded audio: the buffered samples land now
            if (muxAudioTrack >= 0 && muxerStarted && audioSamples != null) {
                val info = MediaCodec.BufferInfo()
                for ((bytes, meta) in audioSamples) {
                    info.set(0, bytes.size, meta.presentationTimeUs, meta.flags)
                    muxer.writeSampleData(muxAudioTrack, ByteBuffer.wrap(bytes), info)
                }
            }
            // audio passthrough after video (sample interleaving is handled by
            // the muxer's timestamps)
            else if (muxAudioTrack >= 0 && muxerStarted) {
                audioExtractor.selectTrack(audioSrcTrack)
                val abuf = ByteBuffer.allocate(1 shl 18)
                val ainfo = MediaCodec.BufferInfo()
                while (true) {
                    val n = audioExtractor.readSampleData(abuf, 0)
                    if (n < 0) break
                    ainfo.set(0, n, audioExtractor.sampleTime,
                        if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(muxAudioTrack, abuf, ainfo)
                    audioExtractor.advance()
                }
            }
            muxer.stop()
            DiagLog.log(context, "video-compress",
                "done: ${out.length() / 1024}KB (target ${targetBytes / 1024}KB)")
            return if (out.length() > 0) out else { out.delete(); null }
        } catch (e: Exception) {
            DiagLog.log(context, "video-compress", "failed: ${e.message} — sending original path")
            out.delete()
            return null
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { outputSurfaceGl?.release() } catch (_: Exception) {}
            try { inputSurfaceGl?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }
}
