# Invariants — do not change these without reading the incident behind them

1. **MMS read-report wire request stays VALUE_NO.** Requesting read reports
   caused phantom duplicate MMS and broke unsolicited read notices (0.9.40,
   fully reverted). The display layer for read state stays.
2. **Never learn own numbers from ingested rows.** Rows written by previous
   apps/restores carry other people's numbers in the From slot. Field log
   07-29: a group member's number entered the learned set; the echo detector
   flagged their real messages as "self" and participant math split their
   group into 1:1 threads. Learning is send-path + import majority vote only.
3. **Self-echo suppression stays a DRY RUN** until a field log shows
   detections firing only on true echoes. Its 0.9.94 live version deleted
   provider rows and ate real messages. Never delete provider rows on a
   heuristic match.
4. **Outgoing recipient lists exclude own numbers** (`sendTargets`), matching
   the engine's EXCLUDE_MY_NUMBER. Participant lists may still CONTAIN the
   own number (deterministic threading on blank-SIM devices) — the exclusion
   happens at send time and in status maps/display, not by rewriting convos.
5. **Raw ContentResolver work runs on Dispatchers.IO.** Room hops threads;
   resolver queries don't (the sendPendingReadRecs freeze).
6. **Sub-screens are Activities**, not fragment backstacks (D-pad focus).
7. **No image resize mid-scroll.** Dimensions persist on parts at ingest;
   rows pre-size from the DB; decode is bounds-only.
8. **Softkeys act only while the bar is shown** (`Softkeys.handleKey` gates on
   `shouldShow`); the capture wizard reads raw key events and is exempt. In
   zoom mode, zoom-owned keys (D-pad, *, #, BACK) are handled before softkeys
   — on some flips a softkey IS the BACK key.
9. **`parseStatuses` never round-trips a field that may contain D/R letters**
   (letters parse to 0). String-level edits only.
10. **The two conversation menus (list long-press, in-thread options) stay
    identical** in items and order, minus list-only actions. A change to one
    is a change to both.
11. **Every non-ingested inbox MMS row logs a drop reason.** No silent exits.
12. **Sync passes skip outgoing provider rows younger than 15 s** (both SMS
    and MMS) — the fan-out registration race.
