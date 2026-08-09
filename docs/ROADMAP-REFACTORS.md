# Refactor roadmap (found in the July 2026 audit — each is its OWN release)

1. **Extract shared conversation menus.** `convoOptions` (MainActivity) and
   `threadOptions` (ThreadActivity) plus their Notifications/Customize
   submenus are near-duplicates kept in sync by rule. Extract a `ConvoMenus`
   builder taking the activity + context-only extras; the parity invariant
   becomes structural. Includes the two add-to-contacts dialogs.
2. **Split Repo.kt (~2300 lines)** into IngestPipeline, SendPipeline,
   TelephonySync, ReportMatcher around the existing section comments. Pure
   moves first, zero behavior change, one section per release.
3. **Split ThreadActivity (~2000 lines):** ComposeBarController (buttons,
   focus chains, softkey modes) and the zoom controller are self-contained.
4. **Typed recipient-status codec** replacing the string field's dual format
   (numeric + D/R letters) and the `parseStatuses` letter-destruction
   footgun. Needs a Room migration; display + report writers move together.
5. **Restore broadcast shape:** two-pass restore so broadcast SMS come back
   as one broadcast message (write rows, re-import, then merge per-member
   1:1 ghosts into a broadcast entity via the backed-up registry).
6. **Settings/prefs backup** (toggles, softkey mapping, chat backgrounds
   with convo-id remap) as an opt-in second file in the backup zip.
7. **deliveryDebug growth cap** (append-only today; clamp to last N lines).
8. **Import qualifiers:** replace repeated fully-qualified
   `io.github...util.X` references with imports. Cosmetic; do alongside 2/3.
Done already: sms-id recycling (dated registry, 0.9.97), silent-drop
logging (0.9.95), backup v2 (0.9.98).
