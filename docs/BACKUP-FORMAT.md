# Backup format (v2)

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
