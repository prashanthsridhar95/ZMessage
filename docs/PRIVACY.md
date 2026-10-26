# Privacy: data inventory and threat model

## Data the app touches

| Data | Where it comes from | Stored? | Leaves the device? |
|---|---|---|---|
| SMS text, sender, time | Android SMS provider (`READ_SMS`) | No: read into memory, re-read on change | No |
| Read / pinned / archived marks, dismissed tips | User actions | Message *ids* only, in private SharedPreferences | No |
| Budgets | User input | Category and amount, private SharedPreferences | No |
| Settings (screenshot protection) | User input | One boolean | No |

There is no account, no analytics and no crash reporter, and no network permission.

## Threats considered

| Threat | Mitigation |
|---|---|
| App exfiltrates messages | No `INTERNET` permission, enforced by a unit test |
| Messages end up in a cloud backup | `allowBackup=false` plus exclude-all backup and transfer rules |
| Someone looks over the shoulder / screen capture / recent-apps preview | Optional `FLAG_SECURE` ("Block screenshots") |
| Malicious or malformed SMS crashes or stalls the app | Classifier never throws, parses at most 4096 characters, fuzz and hostile-input tests |
| Another app drives ours | Only the launcher activity is exported; no services, receivers or providers |
| Reverse engineering | R8 minification and obfuscation in release |
| Sensitive text in logs or bug reports | No logging of message content; diagnostics report masks digits, names, links and handles |

## Known limits

- Anyone who unlocks the phone can open the app. There is no in-app lock yet (biometric lock is a candidate).
- A device with root or a debugger attached can read app memory.
- The masking in the diagnostics report is heuristic; review it before sharing.
