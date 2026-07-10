# D-SMS

A D-pad first SMS/MMS messenger for Android keypad phones (kosher phones, flip phones,
feature-phone style devices) — and it works fine on touch phones too.

Package: `io.github.theonionsarewatching.nova` · minSdk 23 (Android 6) · targetSdk 34

## Why

Stock and mainstream SMS apps assume a touchscreen. D-SMS assumes a D-pad:
every screen is reachable with up/down/left/right/OK, all menus are dialogs
(natively D-pad friendly), the focused item always has a visible outline, and
physical softkeys are first-class citizens.

## Feature summary

**Messaging engine**
- Full default-SMS-app role: sends and receives SMS and MMS (pictures, video, audio, vCards)
- MMS via the maintained `android-smsmms` fork (same engine as Simple SMS Messenger), using the
  system's `SmsManager` send/download path — no APN fiddling on modern devices
- Own Room database as the source of truth: the conversation list and threads open instantly
  with no telephony-provider queries on the UI path
- One-time import of your existing message history with a progress bar
- Reconciliation safety net: a `ContentObserver` watches the system telephony store and pulls in
  anything another app wrote (matched by timestamp window + address), plus a manual
  "Re-sync" button in settings
- Delivery states per message: Sending → Sent → Delivered / Failed / Canceled, with
  per-recipient status detail for broadcast group sends (in message Details)
- Failed messages: OK press offers **Retry**; messages mid-send can be **Canceled** from the
  long-press menu (a later carrier confirmation flips Canceled → Sent, because reality wins);
  canceled messages show a CANCELED flag and OK offers **Resend**
- Scheduled send ("Send later…") with exact alarms, survives reboot, shows inline with a
  scheduled label; press for Send now / Cancel

**Groups**
- Per-conversation choice between **Broadcast** (separate SMS per person, replies arrive 1:1)
  and **Group MMS** (shared thread), changeable mid-conversation — a system line notes the switch
- Default mode for new groups configurable in settings

**D-pad model**
- Conversation list: OK opens, long-press OK opens the full options menu
  (call, pin, archive, mute, block notifications, hide, mark read/unread, block number, delete)
- Thread: focus walks message by message; messages taller than the screen sub-scroll a few
  lines at a time so nothing is skipped; holding up/down accelerates and slows near the ends
- Compose box: grows as you type (max lines configurable); pressing UP from the first line
  collapses it to one line (with a "draft continues" hint) and moves focus into the messages;
  DOWN from the newest message returns to the compose box
- OK on a message: opens the attachment, or the parsed-elements menu, or (plain text, no
  elements) does nothing — consistency by design. Long-press is the universal menu:
  copy, forward, save attachment, lock/unlock, details, delete, plus any elements
- Parsed elements: phone numbers, links, emails and street addresses are extracted **at receive
  time** and by a background pass over imported history — so the menu is instant, no scanning
  when you open it. Links honor an open-automatically / ask / never setting and check whether a
  browser is even installed; addresses open via `geo:` (Waze/Maps)

**Organizing**
- Pin, archive, hide (hidden conversations live in Settings and receive silently)
- Mute ladder: silent notification → no notification → hidden entirely; all still record
- Keyword blocking: messages containing your keywords are stored silently under
  Settings → Blocked messages (restore or delete from there)
- System-level number blocking (Android 7+)
- Recycle bin: deletes are two-step. Configurable auto-empty (7/30/90 days or manual).
  **Locked messages**: deleting one asks an extra confirmation; deleting a whole thread asks
  whether to delete locked messages too or keep them. A message loses its lock the moment it
  enters the bin, and auto-empty does not respect locks (by design).
- Sort (recent / unread first / A-Z / oldest) and filter (unread / unknown senders / groups)
- Full-text search (FTS) over bodies + contact-name matches; opening a result jumps the thread
  to that exact message. The main-screen search bar is off by default (toggle in settings).

**Appearance**
- Material 3 look; on Android 12+ an optional "System" accent follows your wallpaper (Material You)
- Light/dark/system theme, 8 fixed accent colors
- Three message styles: bubbles, full-width accent-bar rows, or plain indent
- Independent sizes for message text and timestamps, list density, overall text scale,
  focus outline thickness
- Layout direction: automatic / force LTR / force RTL (for Hebrew/Yiddish UIs)

**Device profiles**
- Softkey bar (Left/OK/Right labels) shows automatically on keypad devices
  (auto-detect / always / never)
- Key capture screen: press your phone's actual softkeys once and D-SMS remembers their
  key codes — works on devices with nonstandard mappings
- MENU key opens options; CALL key dials the current contact
- Contact names are cached in the DB, refreshed by a contacts ContentObserver and throttled
  periodic checks — the list never waits on the contacts provider

## Building

Everything builds on GitHub Actions — no local Android SDK needed. Every push to `main`
runs **Build debug APK**; grab `Nova-debug` from the run's artifacts. Tagging a release
(`git tag v0.1.0 && git push --tags`) runs **Release** and attaches an APK to a GitHub Release.

Full build, local-build, and release-signing instructions are in **[BUILDING.md](BUILDING.md)**.
No credentials are stored in this repo — release signing reads entirely from GitHub Actions
secrets at build time.

## First run on a device

1. Open the app → it asks to become the **default SMS app** (required by Android to send/receive).
2. Grant Contacts / Phone / Notifications permissions.
3. Battery-optimization prompt (so notifications are instant).
4. Softkey setup — press your phone's left softkey, then its right softkey, then Save
   (regular keys like the D-pad and number keys are rejected automatically). Skippable;
   rerun any time from Settings.
5. One-time import copies your existing messages in (progress bar).

## Architecture notes

- `data/Db.kt` — Room schema (conversations, messages, parts, elements, keywords, contact-name
  cache, FTS index). `data/Repo.kt` — every mutation goes through here.
- `sms/` — the telephony boundary: SMS deliver receiver, MMS engine glue
  (`PushReceiver`/`TransactionService` from the library, `MmsReceiver`/`MmsSentReceiverImpl`
  located via the library's taskAffinity convention), multipart SMS sending with per-part
  sent/delivered PendingIntents, scheduled-send alarms, boot rescheduling.
- MMS binary parts are copied out of the telephony provider into app storage
  (`files/parts/`) at ingest, so thumbnails and the media viewer never touch the provider.
- Image loading is Coil with the **in-memory cache disabled** (low-RAM devices);
  video thumbnails via `VideoFrameDecoder`.
- UI is Android Views (no Compose), all menus are `AlertDialog`s, focus visuals are
  programmatic stroke drawables so the thickness setting applies everywhere.

## Known limits / roadmap (v0.1)

- Built-in D-pad file picker fallback for devices whose system picker is unusable
  (per-device profile flag) — planned; current attach flow uses the system picker
- Dual-SIM per-message SIM selection — planned (sends use the default SIM today)
- Forwarding sends the first attachment only
- Bulk select currently supports delete (forward-many is planned)
- MMS behavior varies by carrier; test on a real SIM (see below)

## Real-device test checklist

- [ ] Default-app prompt appears and sticks after reboot
- [ ] Send/receive plain SMS both directions; multipart (long) SMS arrives as one message
- [ ] Send a photo (MMS) on mobile data; receive a photo; group MMS both modes
- [ ] Delivery report shows Delivered (carrier-dependent)
- [ ] Attach flow round-trips through your device's file picker
- [ ] Softkeys drive the bar after key capture; MENU/CALL keys behave
- [ ] Scheduled message fires after a reboot

## License

Choose one before publishing (MIT/Apache-2.0 recommended). The MMS engine dependency
(`android-smsmms`) is Apache-2.0.
