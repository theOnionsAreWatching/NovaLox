# NovaLox

**A messaging app built for keypad phones.** Texts and picture messages, fully
usable with just the D-pad — made with kosher phones, flip phones, and
button-style Android devices in mind. Works on touchscreens too.

No app store needed. No Google account. Nothing leaves your phone.

---

## Features

- Conversations open immediately, even with years of history
- Full D-pad operation: hold to scroll fast, a date bubble shows your place,
  the selected item has a clear outline
- Physical softkey support with a setup screen (press left, press right, save);
  on-screen buttons as a fallback for phones whose softkeys don't reach apps
- Press a message to act on its contents: call a number, open a link, save a
  contact
- Group messages as one group conversation or as individual replies, set per
  group; view all participants and message or call any of them
- Per-conversation notification tone, vibration, or silence; keyword blocking
- Lock messages against deletion; recycle bin for deleted messages; scheduled
  sending
- Attach pictures, videos, audio, and contact cards — several at once; share
  into the app from the gallery
- Whole-app resize from 70% to 150%, plus separate text size settings
- Built-in update check and installer that works behind filtered networks
- Backup and restore to a single file, including attachments; restore writes
  into the phone's message storage

## Installing

1. On the phone, open **[Releases](../../releases/latest)** and download the
   `NovaLox-vX.Y.Z.apk` file.
2. Open the downloaded file. If the phone asks, allow installing from this
   source (one-time).
3. Open NovaLox and follow the setup: make it the default messaging app, grant
   the permissions it asks for, map your softkeys (or skip), and let it import
   your existing messages.
4. The import can run in the background — press **Run in background** and start
   using the app right away.

## Updating

NovaLox checks for updates about once a week and offers to download them. You
can also check any time in **Settings → Check for updates**. The downloaded
update file is cleaned up automatically after installing (Settings has a switch
for that).

## Moving to a new phone

1. Old phone: **Settings → Back up messages** — save the file somewhere you can
   move it (memory card, computer, Downloads).
2. New phone: install NovaLox, then **Settings → Restore from backup** and pick
   the file.

Restore writes your messages into the phone's real message storage — they
become normal messages, visible to any messaging app, and messages already on
the phone are kept without duplicates.

## Tips

- **A softkey doesn't respond even after setup?** Some phones never deliver
  those keys to apps. Turn the softkey bar off (Settings → Softkey bar →
  Never) and on-screen buttons that work with the D-pad appear instead.
- **Group messages look mixed up, or pictures sit in the wrong chat?** Run
  **Settings → Re-import all messages** once — it rebuilds everything from the
  phone's message storage.
- **Search** from the main screen finds any message. The search bar can be
  hidden in Settings if you never use it.

## Privacy

Your messages stay on your phone. NovaLox has no servers, no ads, no analytics,
and makes no network connections except checking this page for updates (and
sending/receiving MMS through your carrier, of course).

## License

NovaLox is free to use, copy, and share for **noncommercial purposes** —
personal use, family, schools, charities, and the like — under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md).

Using or distributing it **commercially** (for example, preinstalling it on
phones you sell, or bundling it with a paid service) requires permission —
open an issue on this page to ask.

Required Notice: Copyright theOnionsAreWatching
(https://github.com/theOnionsAreWatching/NovaLox)
