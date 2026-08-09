# Feature map (where things live)

| Feature | Entry points |
|---|---|
| Conversation list, long-press menu | `ui/MainActivity.kt` (`convoOptions`) |
| Thread view, in-thread options | `ui/ThreadActivity.kt` (`threadOptions`) — mirrors convoOptions |
| Compose (new message), group-mode picker | `ui/ComposeActivity.kt` |
| D-pad navigation, hold-to-scroll, date bubble | `ui/Base.kt`, `ThreadActivity` |
| Softkey bar + physical mapping wizard | `ui/Base.kt` (`Softkeys`), `ui/settings/Settings.kt` (`SoftkeyConfigActivity`) |
| Send-on-left layout | `ThreadActivity.applyComposeButtonLayout`, `ComposeActivity.updateComposeSoftkeys` |
| T9 contact matching | `ui/MainActivity.kt` suggestion list + `util/ContactsHelper` |
| Message windowing + loading banner | `ThreadActivity` + `MessageAdapter` |
| Bubble styles, accent bar, dark theme | `ui/MessageAdapter.kt` (drawables), `util/Prefs` |
| Chat backgrounds, per-convo tones | Customize menu (both menus), `SoundDialog` |
| Group modes (group-MMS vs broadcast) | set at compose; `Repo.sendText/sendAttachment`, `Sender` |
| Per-recipient delivered/read | `recipientStatuses` (see MESSAGING-PIPELINE) |
| Attachments (photo/video/contact/audio/file) | `AttachOrPaste`, `ThreadActivity.pickAttachment`, `util/MimeExt` |
| Picture viewer + zoom | `ui/MediaViewerActivity.kt` |
| Save to gallery/Downloads/SD | `ui/Saver.kt` (+ MMS settings switch) |
| Scheduled send, locked messages, recycle bin | `Repo` (alarms), entity flags |
| Blocked keywords (modes, numbers, case) | `KeywordEntity`, Advanced settings |
| Backup/restore | `data/BackupHelper.kt` (see BACKUP-FORMAT) |
| Self-updater (GitHub releases) | `util/UpdateChecker.kt` — `releases/latest` redirect; prereleases invisible to users |
| Diagnostics ring buffer | `util/DiagLog` (Settings → export log) |
