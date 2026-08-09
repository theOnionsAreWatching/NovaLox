# Messaging pipeline

## Send — SMS
`Repo.sendText` → entity (status SENDING) → `Sender.sendSmsToAll`: one
provider row per recipient, `DATE` written explicitly. First row links to the
entity (`telephonyId`); further rows register in `BroadcastCopies.recordSms`
with the row date. The sync pass skips outgoing SMS rows younger than 15 s —
that closes the insert→register race that produced duplicate 1:1 messages.

## Send — MMS
`Repo.sendAttachment` / long-text conversion → `Sender.sendMms` → either the
group-MMS path (one send, To = all targets) or broadcast (one send per
recipient; each row registered via `linkRow` the moment it exists, plus a
15 s freshness skip in the MMS sync pass for the same race). From is the
subscription number when clean, else the insert-address-token (strict MMSCs
reject anything else with resp=132). `d_rpt` follows the user setting;
`r_rpt` request is honest but incoming read handling never requests reports
(see INVARIANTS.md).

## Receive — MMS
WAP push → `MmsPushReceiver` (persists indication, auto-downloads, sends
notify-resp/ack) → engine persists the retrieve-conf → `MmsReceiver.
onMessageReceived` → `ingestLatestMmsFromTelephony` → `ingestMms`. Every
non-ingested inbox row logs a `drop tid=… reason=…` line — silent exits are
forbidden after the July-27 vanishing-message incident.

## Participant derivation (ingest)
Received: participants = From + (To minus own numbers); a lone To collapses
to 1:1. Sent: To minus own. Deterministic even when own numbers are unknown
(the own number then stays a participant — harmless for threading, and sends
still exclude it via `sendTargets`).

## Reports and statuses
`recipientStatuses` on a message holds per-recipient state in two formats
that coexist: numeric MsgStatus values (written at send: every group target
starts at SENDING, lifted to SENT at send-conf) and D/R letters (written by
MMS delivery-ind/read-orig-ind per reporting recipient). `parseStatuses` is
numeric-only and DESTROYS letters — any rewrite of the field must be a
string-level edit (see onMmsSent). Display (bubble meta + details) counts
both formats and filters own-number entries.

## Dedup guards, in one place
1. `telephonyId` linking (entity ↔ row)
2. `BroadcastCopies` registry (MMS: id-only, ids never recycle; SMS:
   id + date, same-day match — sms ids DO recycle)
3. 15 s freshness skips (SMS + MMS sync passes)
4. duplicate-132 tr_id check, notification-130 stub suppression
5. self-echo detection (dry-run only; suppression is NOT enabled — the
   detector once flagged a real sender on a polluted device)
