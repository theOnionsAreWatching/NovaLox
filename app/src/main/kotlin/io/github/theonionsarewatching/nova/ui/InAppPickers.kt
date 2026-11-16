package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R

/**
 * our own themed lists instead of Android's "Complete action
 * using" / "Share" chooser (Recorder, Recorder, Select music track, File
 * manager, Contacts, Contacts …). Nothing here starts another app.
 */
object InAppPickers {

    // ---- open the phone's own file manager directly, no chooser ----

    /** An EXPLICIT intent (component set) for the device file manager, so no
     *  "Complete action using" chooser appears. Picks, in order: a package
     *  that looks like a file manager (TCL's included), then the system
     *  DocumentsUI, then any other SYSTEM app that answers — never gallery,
     *  music, recorder or contacts apps. null when there is none. */
    fun fileManagerIntent(activity: Activity): android.content.Intent? {
        val pm = activity.packageManager
        val probes = listOf(
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "*/*" },
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply { type = "*/*" },
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "*/*" }
        )
        val bad = listOf("gallery", "photo", "camera", "music", "audio", "sound",
            "record", "contact", "dialer", "video", "player", "media")
        var best: Pair<Int, android.content.Intent>? = null
        for (probe in probes) {
            val list = try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, 0)
            } catch (_: Exception) { emptyList() }
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == activity.packageName) continue
                val id = (ai.packageName + "/" + ai.name).lowercase()
                val looksFiles = id.contains("filemanager") || id.contains("filemanger") ||
                    id.contains("fileexplorer") || id.contains("filebrowser") ||
                    id.contains(".files") || id.contains("myfiles")
                val docsUi = id.contains("documentsui")
                val system = (ai.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (!looksFiles && !docsUi && bad.any { id.contains(it) }) continue
                val score = when {
                    looksFiles && system -> 4
                    looksFiles -> 3
                    docsUi -> 2
                    system -> 1
                    else -> continue
                }
                if (best == null || score > best.first) {
                    best = score to android.content.Intent(probe).apply {
                        setClassName(ai.packageName, ai.name)
                        putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                        putExtra(android.content.Intent.EXTRA_LOCAL_ONLY, true)
                    }
                }
            }
        }
        return best?.second
    }

    /** an EXPLICIT intent for the phone's own app
     *  answering [probes] (gallery or music), never the file manager /
     *  DocumentsUI, so no chooser and no Files screen. Apps that are the
     *  phone's launcher gallery / music app ([launcherCategory]) win, then
     *  ones whose name looks right ([good]), then other system apps. The
     *  first probe the chosen app answers is used. null when none. */
    fun appPickIntent(
        activity: Activity,
        probes: List<android.content.Intent>,
        launcherCategory: String,
        good: List<String>,
        minScore: Int = 1,
    ): android.content.Intent? {
        val pm = activity.packageManager
        val launcherPkgs = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(launcherCategory), 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
        val filesLike = listOf("documentsui", "filemanager", "filemanger", "fileexplorer",
            "filebrowser", ".files", "myfiles")
        var best: Triple<Int, Int, android.content.Intent>? = null   // score, probe order, intent
        probes.forEachIndexed { order, probe ->
            val list = try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, 0)
            } catch (_: Exception) { emptyList() }
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == activity.packageName) continue
                val id = (ai.packageName + "/" + ai.name).lowercase()
                if (filesLike.any { id.contains(it) }) continue
                val system = (ai.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val score = when {
                    ai.packageName in launcherPkgs -> 4
                    good.any { id.contains(it) } && system -> 3
                    good.any { id.contains(it) } -> 2
                    system -> 1
                    else -> continue
                }
                if (score < minScore) continue
                val b = best
                if (b == null || score > b.first || (score == b.first && order < b.second)) {
                    best = Triple(score, order, android.content.Intent(probe).apply {
                        setClassName(ai.packageName, ai.name)
                    })
                }
            }
        }
        return best?.third
    }

    /** The phone's gallery app, multi-select asked for (pictures + videos
     *  where the gallery allows it). */
    /*
     * ("Gallery" used to show a plain numbered grid). The old probe
     * order put ACTION_PICK on the media store first and accepted ANY system
     * app that answers it (score 1): on some phones that is a bare system
     * pick screen (numbered tiles, no gallery look), not the gallery app.
     * Now: only the real gallery app counts (its launcher entry, or a
     * gallery / photo / album app), and inside it the GET_CONTENT picker
     * (the gallery's own "choose a picture" screen) is tried before PICK.
     * The caller keeps its fallbacks (PICK, GET_CONTENT, image-only PICK),
     * and every route is written to the diagnostics log per phone.
     */
    fun galleryIntent(activity: Activity): android.content.Intent? = appPickIntent(
        activity,
        listOf(
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            },
            android.content.Intent(android.content.Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*") },
            android.content.Intent(android.content.Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "*/*") },
        ).onEach { askMultiple(it) },
        android.content.Intent.CATEGORY_APP_GALLERY,
        listOf("gallery", "photo", "album"),
        minScore = 2,
    )

    /** Ask a gallery for multi-select in every dialect we know: the
     *  standard extra plus the OEM/Gallery3D ones. Unknown extras are
     *  ignored by apps that don't use them. */
    fun askMultiple(i: android.content.Intent): android.content.Intent = i.apply {
        putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra("multi-pick", true)
        putExtra("pick-max-item", 10)
        putExtra("max_select", 10)
        putExtra("max-select-count", 10)
        putExtra("com.android.gallery3d.EXTRA_MULTIPLE", true)
        putExtra("is_multi_select", true)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Every uri a picker returned: ClipData, data, or an EXTRA_STREAM list.
     *  Read access is kept where the provider allows it. */
    fun resultUris(activity: Activity, data: android.content.Intent?): List<android.net.Uri> {
        val out = LinkedHashSet<android.net.Uri>()
        data?.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { out.add(it) } }
        data?.data?.let { out.add(it) }
        try {
            @Suppress("DEPRECATION")
            data?.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        } catch (_: Exception) {}
        for (u in out) try {
            activity.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        return out.toList()
    }

    /** The phone's music app, to pick a track. */
    fun musicIntent(activity: Activity): android.content.Intent? = appPickIntent(
        activity,
        listOf(
            android.content.Intent(android.content.Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/*") },
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*" },
        ),
        android.content.Intent.CATEGORY_APP_MUSIC,
        listOf("music", "audio", "player"),
    )

    const val REQ_MEDIA_PERMISSION = 73
    const val REQ_CONTACTS_PERMISSION = 74

    private var afterGrant: (() -> Unit)? = null

    /** BaseActivity forwards permission results here. */
    fun onPermissionResult(activity: Activity, requestCode: Int, granted: Boolean) {
        if (requestCode != REQ_MEDIA_PERMISSION && requestCode != REQ_CONTACTS_PERMISSION) return
        val next = afterGrant
        afterGrant = null
        if (granted) next?.invoke()
        else DsmsAlertBuilder(activity)
            .setMessage(if (requestCode == REQ_MEDIA_PERMISSION) R.string.media_permission_denied
                else R.string.contacts_permission_denied)
            .setPositiveButton(android.R.string.ok, null)
            // "don't ask again" leaves no way back from here: offer the
            // app's settings page, where the permission can be turned on
            .setNeutralButton(R.string.open_settings) { _, _ ->
                try {
                    activity.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null)))
                } catch (_: Exception) {}
            }
            .show()
    }

    /** Media read permission (Android 11-12: READ_EXTERNAL_STORAGE;
     *  13+: READ_MEDIA_*). Explains first, then asks; runs [then] once granted. */
    fun withMediaPermission(activity: Activity, video: Boolean, then: () -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            if (video) arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
            else arrayOf("android.permission.READ_MEDIA_AUDIO")
        } else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        // Gallery picker fix: D-SMS already has "All files access" on the
        // Flip 2 (for saving to D-SMS folders). That does NOT mark the
        // separate READ_EXTERNAL_STORAGE permission as granted, so the
        // picker asked again every time ("D-SMS needs access…" pop-up, then
        // the system ask; when the ROM answers "denied" straight away, the
        // picker never opened at all). With all-files access the media list
        // is readable already: open the picker straight away.
        if (Build.VERSION.SDK_INT in 30..32 && android.os.Environment.isExternalStorageManager()) {
            then(); return
        }
        ask(activity, perms, REQ_MEDIA_PERMISSION, R.string.media_permission_why, then)
    }

    fun withContactsPermission(activity: Activity, then: () -> Unit) =
        ask(activity, arrayOf(android.Manifest.permission.READ_CONTACTS),
            REQ_CONTACTS_PERMISSION, R.string.contacts_permission_why, then)

    private fun ask(activity: Activity, perms: Array<String>, code: Int, why: Int, then: () -> Unit) {
        if (perms.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            then(); return
        }
        DsmsAlertBuilder(activity)
            .setMessage(why)
            .setPositiveButton(R.string.allow) { _, _ ->
                afterGrant = then
                activity.requestPermissions(perms, code)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------ audio ------------------------------

    /** Recordings and music on the phone, newest first, plus "Record audio"
     *  at the top. [onPick] gets a content:// Uri. */
    fun pickAudio(activity: Activity, onRecord: () -> Unit, onPick: (Uri) -> Unit) {
        withMediaPermission(activity, video = false) {
            val ids = ArrayList<Long>()
            val labels = ArrayList<String>()
            try {
                activity.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION),
                    null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    while (c.moveToNext() && ids.size < 2000) {
                        ids.add(c.getLong(0))
                        val title = c.getString(1)?.takeIf { it.isNotBlank() }
                            ?: c.getString(2) ?: "?"
                        val secs = (c.getLong(3) / 1000).toInt()
                        labels.add(if (secs > 0) "$title  ${secs / 60}:${"%02d".format(secs % 60)}" else title)
                    }
                }
            } catch (_: Exception) {}
            val items = listOf(activity.getString(R.string.attach_menu_record)) + labels
            DsmsAlertBuilder(activity)
                .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.attach_menu_audio)))
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == 0) onRecord()
                    else onPick(ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ids[which - 1]))
                }
                .apply { if (labels.isEmpty()) setMessage(R.string.no_audio_found) }
                .show()
        }
    }

    // ------------------------------ contacts ------------------------------

    /** Contacts A-Z (one row each, no duplicate apps). [onPick] gets the
     *  same contact Uri the system picker would return. */
    fun pickContact(activity: Activity, onPick: (Uri) -> Unit) {
        withContactsPermission(activity) {
            val ids = ArrayList<Long>()
            val names = ArrayList<String>()
            try {
                activity.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        ids.add(c.getLong(0)); names.add(n)
                    }
                }
            } catch (_: Exception) {}
            if (names.isEmpty()) {
                DsmsAlertBuilder(activity).setMessage(R.string.no_contacts_found)
                    .setPositiveButton(android.R.string.ok, null).show()
                return@withContactsPermission
            }
            DsmsAlertBuilder(activity)
                .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.attach_menu_contact)))
                .setItems(names.toTypedArray()) { _, which ->
                    onPick(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, ids[which]))
                }
                .show()
        }
    }
}
