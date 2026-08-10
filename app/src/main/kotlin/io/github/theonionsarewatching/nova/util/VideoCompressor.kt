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

    private const val MAX_DIM = 640
    private const val MIN_VIDEO_BPS = 250_000

    /** Returns the compressed file, or null when compression isn't possible
     *  or didn't help. [targetBytes] should be the carrier max minus headroom. */
    fun compress(context: Context, src: File, targetBytes: Long): File? {
        if (!src.exists() || src.length() <= targetBytes) return null
        val out = File(context.cacheDir, "vc_${System.currentTimeMillis()}.mp4")
        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
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
            val durationUs = try { srcFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
            if (durationUs <= 0) {
                DiagLog.log(context, "video-compress", "unknown duration — skipping")
                return null
            }
            val scale = (MAX_DIM.toFloat() / maxOf(srcW, srcH)).coerceAtMost(1f)
            // encoders want even dimensions
            val dstW = ((srcW * scale).toInt() / 2) * 2
            val dstH = ((srcH * scale).toInt() / 2) * 2
            // audio budget: reserve its actual byte share when we pass it through
            var audioBytes = 0L
            var audioFormat: MediaFormat? = null
            var audioSrcTrack = -1
            audioExtractor = MediaExtractor().apply { setDataSource(src.absolutePath) }
            for (i in 0 until audioExtractor.trackCount) {
                val f = audioExtractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        audioSrcTrack = i; audioFormat = f
                        val br = try { f.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { 96_000 }
                        audioBytes = br.toLong() * durationUs / 8_000_000L
                    } else {
                        DiagLog.log(context, "video-compress",
                            "audio codec $mime is not AAC — audio dropped")
                    }
                    break
                }
            }
            val videoBps = (((targetBytes - audioBytes) * 8 * 1_000_000L / durationUs) * 9 / 10)
                .toInt().coerceAtLeast(MIN_VIDEO_BPS)
            DiagLog.log(context, "video-compress",
                "src=${src.length() / 1024}KB ${srcW}x${srcH} dur=${durationUs / 1000000}s -> " +
                    "${dstW}x${dstH} @${videoBps / 1000}kbps audio=${if (audioSrcTrack >= 0) "aac-pass" else "none"}")

            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dstW, dstH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(srcFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(srcFormat, inputSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
                // drain the decoder onto the encoder's surface
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                    if (outIdx >= 0) {
                        val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIdx, bufInfo.size > 0)
                        if (eos) { decoderDone = true; encoder.signalEndOfInputStream() }
                    }
                }
                // drain the encoder into the muxer
                val encIdx = encoder.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioSrcTrack >= 0 && audioFormat != null) {
                            muxAudioTrack = muxer.addTrack(audioFormat)
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
            // audio passthrough after video (sample interleaving is handled by
            // the muxer's timestamps)
            if (muxAudioTrack >= 0 && muxerStarted) {
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
            return if (out.length() in 1..targetBytes) out else {
                DiagLog.log(context, "video-compress", "result still over target — using original")
                out.delete(); null
            }
        } catch (e: Exception) {
            DiagLog.log(context, "video-compress", "failed: ${e.message} — sending original path")
            out.delete()
            return null
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }
}
