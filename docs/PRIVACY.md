# Privacy: data inventory and threat model

## Data the app touches

| Data | Where it comes from | Stored? | Leaves the device? |
|---|---|---|---|
| SMS text, sender, time | Android SMS provider (`READ_SMS`) | Read into memory and re-read on change. Also remembered, with how it was read, in a local database where every entry is encrypted with a key held in the Android Keystore; the database is excluded from backups and is rebuilt on every app update | No |
| Read / pinned / archived marks, dismissed tips | User actions | Message *ids* only, in private SharedPreferences | No |
| Pictures chosen for senders | The person's own photos via the system picker (no permission) | Cropped to 256px in private storage | No |
| Brand logos (on by default, can be turned off) | A public icon service, asked for a well-known brand's website name only | Cached in private storage | The website name is sent, never message content |
| Picture for a sender, searched on the web (only when the person taps "From the web") | Google Images, searched for the sender's name in an in-app page; the picture the person taps is downloaded once | Cropped to 256px in private storage | The sender's name is the search text, as if typed into Google; nothing else |
| Market prices (off until switched on in Settings) | A public price service (Yahoo Finance), asked for the tickers in your watch list, and, while you type in the add-stock box, for matching names | Prices held in memory; the list (ticker, name, exchange) is in private SharedPreferences; what you type in the add-stock box is not stored, only sent while typing | The tickers and the letters you type into the add-stock box are sent, never message content |
| Entries you add by hand, payments left out of totals, transfers you link | User input | Private SharedPreferences | No |
| Text you share to the app ("200 for auto") | Another app's Share sheet | Read once to pre-fill the entry form; not stored or logged unless you save the entry. The Quick add widget and the Add entry quick-settings tile only open the form or save an entry you tapped, on the phone | No |
| Cards and bank accounts (last four digits, bank, name, statement date) | User input in Settings; never the full number | Private SharedPreferences | No |
| Sender / receiver categories | User input | Name and category, private SharedPreferences | No |
| Budgets | User input | Category and amount, private SharedPreferences | No |
| Chat (RCS) messages | Incoming chat notifications from Google / Samsung Messages, only after the user allows notification access in system settings | Sender, text and time of up to 2000 messages, in a file in the app's private storage | No |
| New texts as they arrive (live alerts, off until chosen) | The phone's `SMS_RECEIVED` broadcast, `RECEIVE_SMS` | No: read in memory to spot a code or card bill, then dropped | No |
| Reading a very large inbox (20,000+ messages) month by month, newest first | `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`: a visible notification while it runs, stopped by itself when every month is read | No: it reads the same messages the screens do, into the same encrypted on-phone cache | No |
| Money plan (rough salary and its day, regular outflows such as parents or charity, investments, savings goal) | User input in Settings | Names and amounts in private SharedPreferences | No |
| Wishlist (things to buy, their price and category) | User input in Settings | Names and amounts in private SharedPreferences | No |
| Split payments (friends' names, their shares, which are paid) | User input in the message sheet | Names and amounts in private SharedPreferences; nobody is contacted or asked for money | No |
| Savings goals (name, target, saved so far, date) | User input on the Accounts tab | Names and amounts in private SharedPreferences | No |
| Reminders (what, amount, day, monthly or not) | User input on the Bills tab | Private SharedPreferences; mentioned in the morning digest if that is on, otherwise only shown in the app | No |
| Daily and monthly spending limits | User input in Settings | Category and amount in private SharedPreferences | No |
| Muted senders | User action | Sender names, private SharedPreferences | No |
| Settings (screenshot protection, theme, app lock, morning digest, hide amounts and "ask for authentication to show amounts") | User input | Booleans and one choice | No |
| Morning digest and widget (off until chosen) | Built on the phone from the inbox | A notification text shown on the lock screen only as a private notification; the widget draws the same lines on the home screen | No |
| Paired watch (optional; only in a build made with `-PwithWear=true`) | `WatchSnapshot` (`:domain`): today's budget left, this month against last, the next three bills or EMIs (name, amount, days), the count of items to review, the latest total balance, and when it was made. Never message text, sender or account number. With "Hide amounts" on, no amount is sent at all | Latest copy only, in the Wear OS Data Layer on the phone and in private preferences on the watch | Only to your own paired watch, over the Data Layer's local Bluetooth/Wi-Fi link; no server. The default phone build has no Wear code and no Google Play services dependency |
| Automatic backup of your setup (off until you switch it on, Settings, Automatic backup) | The same setup as "Export my setup" plus the sender pictures you chose, never messages, the message-reading cache, logs or the developer file | One file, encrypted with AES-256-GCM using a key stretched (PBKDF2-HMAC-SHA256, 600,000 rounds) from a 6-digit PIN or a passphrase of your choice. The PIN is never stored; only the stretched key is kept, sealed with a Keystore key, so scheduled backups can run. The file is kept at `files/auto_backup/zyke-backup.bin` | Option A: Android's own Auto Backup may upload that one file (encrypted) to your Google account; no sign-in and no `INTERNET` needed. Option B (off by default): that one file is uploaded to the private app folder of your own Google Drive (scope `drive.appdata`, which sees nothing else in your Drive); the last 7 are kept. No analytics, no other data, no account name or token is stored by the app |
| Parsing report (masked sample messages) | Created on request in Settings | A text file in the private cache until the next report | Only if the user shares it, through the system share sheet |

Suggestions, spam scoring, search phrases, alerts and summaries are computed on the phone from messages already in memory; nothing is downloaded or sent. An on-device language model (Gemini Nano) is supported through a small interface but is only used when the phone provides one, and every answer it gives is checked against the message text.

There is no account, no analytics and no crash reporter. The only network use is the logo setting (on by default, can be turned off), the market strip and its stock-name suggestions (off until you turn it on; what you type in the add-stock box is sent to Yahoo Finance while you type, and is not stored) and the optional backup to your own Google Drive (off until you turn it on and sign in).

## Threats considered

| Threat | Mitigation |
|---|---|
| App exfiltrates messages | `INTERNET` is used by three files: `LogoFetcher` (a brand's website name), `QuoteFetcher` (tickers from your watch list, and what you type in the add-stock box to suggest names) and `DriveBackupClient` (opt-in: one PIN-encrypted backup file to your own Drive's private app folder); a unit test enforces that no other file touches the network and which hosts they may call |
| The cache database is copied off the phone and read | Each entry is AES-256-GCM encrypted with a Keystore key that cannot be exported, so the file alone is unreadable; backups and device transfer exclude databases; a damaged or old entry is dropped, never trusted |
| Messages end up in a cloud backup | Android Auto Backup is on only to carry one file, `auto_backup/zyke-backup.bin`, which holds the encrypted setup and never messages; the backup and transfer rules include only that file and exclude preferences, databases, every other file, external storage and the app root, and a unit test pins this. Cloud backup is skipped on a phone that cannot encrypt it |
| A backup file is stolen and guessed at | The file is AES-256-GCM encrypted with a PBKDF2 (600,000 rounds) key from your PIN. A 6-digit PIN has only 1,000,000 possibilities: this keeps casual finders out, but someone holding the file could try them all offline. The app says so and offers a long passphrase instead; a wrong PIN on the restore screen waits a second per try |
| Someone looks over the shoulder / screen capture / recent-apps preview | Optional `FLAG_SECURE` ("Block screenshots") |
| Malicious or malformed SMS crashes or stalls the app | Classifier never throws, parses at most 4096 characters, fuzz and hostile-input tests |
| Another app drives ours | Only the launcher activity is exported; the notification listener service and the three receivers (widget, morning alarm, restart) are not exported, and a unit test pins the list |
| A rogue app fakes an incoming text to the live-alerts receiver | It is the only exported receiver, guarded by the system-only `BROADCAST_SMS` permission, disabled until the person opts in, and it only ever shows a notification |
| A code shows on the lock screen or in the clipboard preview | The notification is private (the lock screen says "A new one-time code"), and the copied code is marked sensitive |
| Someone sees your money figures over your shoulder | Optional "Hide amounts": every amount in the app (screens, message text, snackbars, spoken descriptions) shows as dots, and so do notifications and the widget. Showing them asks for the phone's own screen lock (fingerprint, face, PIN, pattern or password) through the system's confirm-credential screen, then reveals for 60 seconds, or until you leave the app. Nothing about the check is stored; the app never sees a fingerprint, face or PIN. A phone with no screen lock falls back to a tap that shows amounts for 5 seconds |
| Someone else picks up an unlocked phone | Optional app lock: the phone's own fingerprint, face or screen-lock prompt after 30 seconds away |
| Digest or widget shows private details | The notification is private on the lock screen; the widget is something you add yourself and shows only short lines such as "Insurance expires tomorrow" |
| Reverse engineering | R8 minification and obfuscation in release |
| Sensitive text in logs or bug reports | No logging of message content; the parsing report masks digits, names, links and handles and is shared only by the user, through a private non-exported `FileProvider` limited to one cache folder |

## Known limits

- A 6-digit backup PIN is weak against someone who has the backup file itself (one million guesses, each slowed by 600,000 rounds). Use a long passphrase for real protection. A forgotten PIN or passphrase cannot be recovered by anyone.
- Android decides when its Auto Backup uploads the file; the app only controls how often the local file is refreshed. Turning that option off deletes the local file but cannot delete a copy Android already uploaded.
- Backups already uploaded to your Drive stay there when you switch Drive backup off; remove them in Google Drive settings, Manage apps.
- The app lock uses the phone's screen-lock prompt, so anyone who knows the phone's PIN can open the app. It is off by default.
- "Hide amounts" covers what the app draws and posts. A notification posted while amounts were shown keeps its text, and a year-in-review picture or report you choose to share carries real figures.
- The morning digest alarm is inexact and, after a restart, comes back only because the app registers for the boot broadcast.
- A device with root or a debugger attached can read app memory.
- The masking in the diagnostics report is heuristic; review it before sharing.
