# NovaLox Architecture

Keypad-first SMS/MMS app. Min SDK 23, target 34. Room schema v8.
Engine: vendored fork of `tibbi/android-smsmms` (in `com/klinker/...`) plus an
in-app MMS builder (`SystemMmsSender.kt`) that mirrors AOSP Bugle's address
handling.

## The two stores

The **Android telephony provider** (`content://sms`, `content://mms`) is the
system of record — every message must exist there or other apps/backup tools
won't see it. The **app Room DB** (`data/Db.kt`) is a derived view optimized
for display: one `MessageEntity` per logical message (a broadcast is ONE
entity even though it's N provider rows), plus conversations, parts, elements,
keywords. `telephonyId` links an entity to its provider row.

Everything that keeps the two stores consistent lives in `data/Repo.kt`:

- **ingestMms / SMS ingest** — provider row → app entities. Participant
  derivation, dedup guards, dimension capture, diagnostics.
- **syncRecentFromTelephony** — observer-driven recovery pass (48 h window)
  that catches rows the primary paths missed. Full history re-scan lives in
  `importFromTelephony` (used by re-import and restore).
- **send paths** — `sendText` / `sendAttachment` create the entity first,
  then `sms/Sender.kt` writes provider rows and hands MMS to the engine.
- **report matching** — `handleMmsIndication` + SMS status receivers map
  delivery/read reports back onto entities (`recipientStatuses`).

## Threading model

Room DAOs suspend. Raw `ContentResolver` calls do NOT thread-hop — every one
must be wrapped in `withContext(Dispatchers.IO)` (a past main-thread freeze
came from exactly this). UI is Activities only; sub-screens are separate
Activities, never fragment backstacks (D-pad focus bugs).

## Identity: own numbers

`ownNumbers` = SIM-reported numbers + a learned set. Learned entries come
ONLY from (a) the send path (`SystemMmsSender` — the From we computed
ourselves) and (b) an import-time majority vote across sent rows (≥3 rows and
≥20 % of observations). Never from individual ingested rows — see
INVARIANTS.md for the field incident that rule comes from. Outgoing recipient
lists always exclude own numbers (`Repo.sendTargets`).

## Directory map

    ui/            Activities, MessageAdapter, Softkeys (Base.kt), dialogs
    ui/settings/   Preference screens + softkey capture wizard
    data/          Db.kt (entities/DAOs), Repo.kt (the pipeline), BackupHelper
    sms/           Sender (send orchestration), Receivers, MmsPushReceiver
    util/          Prefs, PhoneUtils, BroadcastCopies, DiagLog, CarrierMms...
    com/klinker/   vendored engine + SystemMmsSender (our builder)
