# Privacy: data inventory and threat model

## Data the app touches

| Data | Where it comes from | Stored? | Leaves the device? |
|---|---|---|---|
| SMS text, sender, time | Android SMS provider (`READ_SMS`) | No: read into memory, re-read on change | No |
| Read / pinned / archived marks, dismissed tips | User actions | Message *ids* only, in private SharedPreferences | No |
| Cards and bank accounts (last four digits, bank, name, statement date) | User input in Settings; never the full number | Private SharedPreferences | No |
| Sender / receiver categories | User input | Name and category, private SharedPreferences | No |
| Budgets | User input | Category and amount, private SharedPreferences | No |
| Chat (RCS) messages | Incoming chat notifications from Google / Samsung Messages, only after the user allows notification access in system settings | Sender, text and time of up to 2000 messages, in a file in the app's private storage | No |
| Settings (screenshot protection, theme) | User input | One boolean and one choice | No |
| Parsing report (masked sample messages) | Created on request in Settings | A text file in the private cache until the next report | Only if the user shares it, through the system share sheet |

Suggestions, spam scoring, search phrases, alerts and summaries are computed on the phone from messages already in memory; nothing is downloaded or sent. An on-device language model (Gemini Nano) is supported through a small interface but is only used when the phone provides one, and every answer it gives is checked against the message text.

There is no account, no analytics and no crash reporter, and no network permission.

## Threats considered

| Threat | Mitigation |
|---|---|
| App exfiltrates messages | No `INTERNET` permission, enforced by a unit test |
| Messages end up in a cloud backup | `allowBackup=false` plus exclude-all backup and transfer rules |
| Someone looks over the shoulder / screen capture / recent-apps preview | Optional `FLAG_SECURE` ("Block screenshots") |
| Malicious or malformed SMS crashes or stalls the app | Classifier never throws, parses at most 4096 characters, fuzz and hostile-input tests |
| Another app drives ours | Only the launcher activity is exported; the notification listener service is not exported and is only bound by the system once the user grants access |
| Reverse engineering | R8 minification and obfuscation in release |
| Sensitive text in logs or bug reports | No logging of message content; the parsing report masks digits, names, links and handles and is shared only by the user, through a private non-exported `FileProvider` limited to one cache folder |

## Known limits

- Anyone who unlocks the phone can open the app. There is no in-app lock yet (biometric lock is a candidate).
- A device with root or a debugger attached can read app memory.
- The masking in the diagnostics report is heuristic; review it before sharing.
