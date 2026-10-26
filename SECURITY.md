# Security and privacy

Zyke Message reads the SMS on the device to organise payments, bills and OTPs. Everything happens on the
phone.

## What the app guarantees

- **No network.** The app declares no `INTERNET` permission, so it cannot send anything off the device.
  `ManifestPolicyTest` fails the build if that changes.
- **Least privilege.** The only permission is `READ_SMS`. It never writes to, sends or deletes messages.
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

When sharing an SMS that is mis-parsed, use *Settings → Parsing health → Copy report*, which masks digits,
names, links and handles. Check the result before sending it.
