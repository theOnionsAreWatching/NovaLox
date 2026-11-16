# Backup format (v3)

Zip: `data.json` + `parts/<file>` attachments.

data.json (JsonWriter, unknown names must be skipValue'd by readers):
- `version` (2)
- `conversations[]`: convoKey, addresses, cachedNames, cachedPhotoUri,
  isGroup, groupMode, customName, snippet*, unreadCount, pinned, archived,
  muted, notifBlocked, hidden, draft, customTone, vibrateMode
- `messages[]`: address, body, date, isMine, status, read, locked,
  deletedAt?, isMms, subId, scheduledAt?, blockedByKeyword,
  recipientStatuses, deliveryDebug?, elementsExtracted, telephonyId?,
  telephonyIsMms
- `parts[]`: messageId, mimeType, fileName, storedName, size
- `keywords[]`: plain strings (kept so OLD readers don't crash)
- `keywords2[]`: {keyword, mode, numbers, caseSensitive} (preferred)
- `broadcastCopies`: the registry string

## Restore phases
1. Write messages into the SYSTEM store (per-recipient SMS rows; MMS via
   `insertSystemMms`). Rows carrying app-only state (locked, statuses,
   delivery trail, richer-than-Sent status) are inserted singly so their ids
   are captured.
2. Merge `broadcastCopies` into the live registry (BEFORE re-import — on a
   same-phone restore the old fan-out rows survive and need their entries),
   wipe the app DB, `importFromTelephony` re-derives everything. Part
   dimensions and message elements REGENERATE here — they're intentionally
   not in the backup.
3. Re-apply app-only knowledge by captured row id: locked, recipientStatuses,
   deliveryDebug, status lift (Sent → Delivered/Read/Failed, upward only),
   conversation settings + customName, keywords, scheduled messages
   (+ `rescheduleAllAlarms`).

Known wart: restored broadcast SMS re-import as per-member 1:1 messages (the
registry can't reference app messages that don't exist mid-restore). Needs a
two-pass link; tracked in ROADMAP-REFACTORS.md.


## v3

Backward compatible: v2 readers only open `data.json` and `parts/`, and
skipValue unknown names, so they restore a v3 zip unchanged (an SMS-only or
MMS-only zip just has fewer messages). v3 readers still restore v1/v2 zips
(no manifest, no settings: treated as `content = all`, settings untouched).

New zip entries (written first / after data.json):
- `manifest.json`: `{format:"nova-backup", version:3, content:"all|sms|mms",
  created:<ms>, app:"<versionName>", hasSettings:true}`
- `settings.json`: same format as a stand-alone settings file (below).

New data.json field: `content` ("all" / "sms" / "mms").
- `sms`: messages with isMms=0 only; `parts[]` is empty and no `parts/` files.
- `mms`: messages with isMms=1, their parts and files.
- `conversations[]` and keywords are always written in full.
- `elements[]` are limited to the chosen kind.

Export streams: messages, parts and elements are read in pages of 500 rows
(keyset by id) and written with JsonWriter straight into the zip. Nothing is
loaded whole.

Restore of a partial backup: Phase 1 only ADDS rows to the system store and
Phase 2 rebuilds the app DB from the whole system store, so the kind not in
the zip stays as it is on the phone. Caveat: app-only state of the other kind
(locked flag, per-recipient status, bin, scheduled) is not in the zip and is
lost by the Phase 2 wipe.

MMS duplicate check (fixed): the system MMS date has 1-second precision, so
`(dateSec, box)` is not unique. Restore now counts system rows per
`(dateSec, box)` and skips a backup MMS only while that count lasts; repeats
inside the backup itself are caught by `date|box|address|bodyHash|partNames`.
Two MMS sent in the same second are both restored.

If `settings.json` is present it is applied after Phase 3.

## Settings file (`nova-settings-YYYYMMDD-HHmm.json`, also `settings.json` in the zip)

```
{ "format": "nova-settings", "version": 1, "app": "<versionName>", "saved": <ms>,
  "prefs": { "<key>": { "t": "b|i|l|f|s|ss", "v": <value> } } }
```
`ss` = string set (array). Applying writes the listed keys only (others are
left alone). Not saved: first-run / migration flags, update-check state,
learned own numbers, retry queue, broadcast registry (it is in data.json),
drafts and per-chat backgrounds (keyed by conversation ids that change), and
any value pointing into app-private storage (the picture isn't in the file).
