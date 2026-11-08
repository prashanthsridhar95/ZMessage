# Security and privacy

Zyke Message reads the SMS on the device to organise payments, bills and OTPs. Everything happens on the
phone.

## What the app guarantees

- **Almost no network.** The `INTERNET` permission exists for two settings: "Fetch brand logos" (on by default, can be turned off) and the market strip (off until you turn it on). Only two files can use it. `LogoFetcher.kt` sends a well-known brand's website name to a public icon service; `QuoteFetcher.kt` sends the tickers in your watch list (such as `RELIANCE.NS`) to Yahoo Finance's public price service, and, while you type in the add-stock box (market strip switched on only), the few letters typed so it can suggest names; that text is not stored. Neither ever sends message text, numbers or amounts, and a unit test keeps network code out of every other file.
  `ManifestPolicyTest` fails the build if that changes.
- **Least privilege.** `READ_SMS` reads your messages; it never writes to or deletes them. `RECEIVE_SMS` (code alerts) and `SEND_SMS` ("Share codes with trusted people") are asked for only when you switch those features on; sharing sends only a login code and the service name, by SMS, to the people you chose, and never a bank, UPI, card, payment, government, insurer or phone-company message. "Check balance by SMS" also uses `SEND_SMS`, asked for only when you first tap Send: it texts your bank's enquiry number the exact words you confirmed (never a PIN, MPIN or password, which Zyke refuses to store or send), at most once per account every 2 minutes, never on a schedule. Google Play restricts `SEND_SMS`, so those features are for builds installed directly.
- **Nothing leaves unless you send it.** The one exception is the masked parsing report you choose to share.
  It goes through a private, non-exported `FileProvider` limited to one cache folder, with read access granted
  to the app you pick for that one file only.
- **Backups are opt-in and encrypted.** Android Auto Backup is on only to carry one file, `files/auto_backup/zyke-backup.bin` (the setup, encrypted with a key from your PIN); every other file, preference and database is excluded. Backup to your own Google Drive is off until you switch it on. See `docs/PRIVACY.md`.
- **Small footprint of stored data.** The app stores only message *ids* (read / pinned / archived marks),
  dismissed tips, budgets and two settings, in app-private storage. Message text is never persisted.
- **Release hardening.** Release builds are minified and obfuscated with R8.
- **Screenshot protection.** Settings has a switch that blocks screenshots and hides the app in the
  recent-apps view.

See [docs/PRIVACY.md](docs/PRIVACY.md) for the data inventory.

## Reporting a vulnerability

Please do not open a public issue. Email the maintainer (see the repository owner's profile) with the details
and steps to reproduce. You will get an acknowledgement within a few days.

When sharing an SMS that is mis-parsed, use *Settings → Parsing health → Share report*. It writes a text file
with digits, names, links and handles masked and opens the system share sheet, so you choose where it goes.
Check the file before sending it.
