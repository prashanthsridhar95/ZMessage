# Security and privacy

Zyke Message reads the SMS on the device to organise payments, bills and OTPs. Everything happens on the
phone.

## What the app guarantees

- **Almost no network.** The `INTERNET` permission exists for two settings: "Fetch brand logos" (on by default, can be turned off) and the market strip (off until you turn it on). Only two files can use it. `LogoFetcher.kt` sends a well-known brand's website name to a public icon service; `QuoteFetcher.kt` sends the tickers you typed into your watch list (such as `RELIANCE.NS`) to a public price service. Neither ever sends message text, numbers or amounts, and a unit test keeps network code out of every other file.
  `ManifestPolicyTest` fails the build if that changes.
- **Least privilege.** The only permission is `READ_SMS`. It never writes to, sends or deletes messages.
- **Nothing leaves unless you send it.** The one exception is the masked parsing report you choose to share.
  It goes through a private, non-exported `FileProvider` limited to one cache folder, with read access granted
  to the app you pick for that one file only.
- **No backups.** `allowBackup` is off and the backup / device-transfer rules exclude all app data.
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
