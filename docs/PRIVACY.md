# Privacy: data inventory and threat model

## Data the app touches

| Data | Where it comes from | Stored? | Leaves the device? |
|---|---|---|---|
| SMS text, sender, time | Android SMS provider (`READ_SMS`) | Read into memory and re-read on change. Also remembered, with how it was read, in a local database where every entry is encrypted with a key held in the Android Keystore; the database is excluded from backups and is rebuilt on every app update | No |
| Read / pinned / archived marks, dismissed tips | User actions | Message *ids* only, in private SharedPreferences | No |
| Pictures chosen for senders | The person's own photos via the system picker (no permission) | Cropped to 256px in private storage | No |
| Brand logos (on by default, can be turned off) | A public icon service, asked for a well-known brand's website name only | Cached in private storage | The website name is sent, never message content |
| Picture for a sender, searched on the web (only when the person taps "From the web") | Google Images, searched for the sender's name in an in-app page; the picture the person taps is downloaded once | Cropped to 256px in private storage | The sender's name is the search text, as if typed into Google; nothing else |
| Market prices (off until switched on in Settings) | A public price service (Yahoo Finance), asked for the tickers in your watch list only | Held in memory; the list itself is in private SharedPreferences | The tickers are sent, never message content |
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
| Settings (screenshot protection, theme, app lock, morning digest) | User input | Booleans and one choice | No |
| Morning digest and widget (off until chosen) | Built on the phone from the inbox | A notification text shown on the lock screen only as a private notification; the widget draws the same lines on the home screen | No |
| Parsing report (masked sample messages) | Created on request in Settings | A text file in the private cache until the next report | Only if the user shares it, through the system share sheet |

Suggestions, spam scoring, search phrases, alerts and summaries are computed on the phone from messages already in memory; nothing is downloaded or sent. An on-device language model (Gemini Nano) is supported through a small interface but is only used when the phone provides one, and every answer it gives is checked against the message text.

There is no account, no analytics and no crash reporter. The only network use is the logo setting (on by default, can be turned off) and the market strip (off until you turn it on).

## Threats considered

| Threat | Mitigation |
|---|---|
| App exfiltrates messages | `INTERNET` is used by two files: `LogoFetcher` (a brand's website name) and `QuoteFetcher` (tickers from your watch list); a unit test enforces that no other file touches the network and which hosts they may call |
| The cache database is copied off the phone and read | Each entry is AES-256-GCM encrypted with a Keystore key that cannot be exported, so the file alone is unreadable; backups and device transfer exclude databases; a damaged or old entry is dropped, never trusted |
| Messages end up in a cloud backup | `allowBackup=false` plus exclude-all backup and transfer rules |
| Someone looks over the shoulder / screen capture / recent-apps preview | Optional `FLAG_SECURE` ("Block screenshots") |
| Malicious or malformed SMS crashes or stalls the app | Classifier never throws, parses at most 4096 characters, fuzz and hostile-input tests |
| Another app drives ours | Only the launcher activity is exported; the notification listener service and the three receivers (widget, morning alarm, restart) are not exported, and a unit test pins the list |
| A rogue app fakes an incoming text to the live-alerts receiver | It is the only exported receiver, guarded by the system-only `BROADCAST_SMS` permission, disabled until the person opts in, and it only ever shows a notification |
| A code shows on the lock screen or in the clipboard preview | The notification is private (the lock screen says "A new one-time code"), and the copied code is marked sensitive |
| Someone else picks up an unlocked phone | Optional app lock: the phone's own fingerprint, face or screen-lock prompt after 30 seconds away |
| Digest or widget shows private details | The notification is private on the lock screen; the widget is something you add yourself and shows only short lines such as "Insurance expires tomorrow" |
| Reverse engineering | R8 minification and obfuscation in release |
| Sensitive text in logs or bug reports | No logging of message content; the parsing report masks digits, names, links and handles and is shared only by the user, through a private non-exported `FileProvider` limited to one cache folder |

## Known limits

- The app lock uses the phone's screen-lock prompt, so anyone who knows the phone's PIN can open the app. It is off by default.
- The morning digest alarm is inexact and, after a restart, comes back only because the app registers for the boot broadcast.
- A device with root or a debugger attached can read app memory.
- The masking in the diagnostics report is heuristic; review it before sharing.
