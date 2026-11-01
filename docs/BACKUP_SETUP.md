# Setting up backup to Google Drive

The Android Auto Backup option needs nothing from you. The **Back up to Google Drive** option needs a Google Cloud project that tells Google "this app (package `com.zyke.message`, signed with this key) may ask for the `drive.appdata` permission". There is no client secret, no `google-services.json` and nothing to put in the app's source: Google recognises the app by its package name and signing key.

`drive.appdata` is a non-sensitive scope. It only lets the app see its own hidden folder in the person's Drive, so no Google verification review is needed to use it (the consent screen can stay in "Testing" for your own use).

## 1. Create the project and turn on the Drive API

1. Open <https://console.cloud.google.com/> and sign in with the Google account you want to own the project.
2. Project picker (top bar) -> **New project** -> name it `Zyke Message` -> **Create**, then select it.
3. **APIs & Services -> Library** -> search **Google Drive API** -> **Enable**.

## 2. OAuth consent screen

1. **APIs & Services -> OAuth consent screen** (in newer consoles: **Google Auth platform -> Branding / Audience**).
2. User type **External** -> **Create**.
3. App name `Zyke Message`, user support email and developer contact email: yours. Save.
4. Scopes: add `.../auth/drive.appdata` ("See, create, and delete its own configuration data in your Google Drive"). Save.
5. Publishing status **Testing**. Under **Test users** add every Google account that will use Drive backup (including yours). Only test users can sign in while it is in Testing. (Testing tokens for non-sensitive scopes work as normal; if you later publish the app, no review is needed for this scope.)

## 3. Create the Android OAuth client

You need the SHA-1 fingerprint of the key the APK is signed with. Create one client per key (debug now, release later).

### Debug key (what CI builds and local debug builds use)

All debug builds, CI and local, are signed with the shared keystore committed at `app/debug.keystore` (see `signingConfigs.debug` in `app/build.gradle.kts`: store password `android`, alias `androiddebugkey`, key password `android`). Print its SHA-1:

```
keytool -list -v -keystore app/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

At the time of writing it is:

```
SHA1: 43:4C:17:27:C8:22:3E:1F:50:FA:66:7F:68:10:57:4F:4E:D4:A4:27
```

(Run the command to be sure; if the keystore is ever replaced, the value changes.)

### Create the client

1. **APIs & Services -> Credentials -> Create credentials -> OAuth client ID**.
2. Application type **Android**.
3. Name: `Zyke Message (debug)`.
4. Package name: `com.zyke.message`.
5. SHA-1 certificate fingerprint: the value above. **Create**.
6. Nothing to download and nothing to add to the app.

It can take a few minutes (occasionally longer) for Google to start accepting a new client.

### Release key (later)

When the release signing key exists (the `RELEASE_KEYSTORE_PATH` secret used by CI), repeat step 3 with:

```
keytool -list -v -keystore <release keystore> -alias <release alias> | grep SHA1
```

and create a second Android client `Zyke Message (release)` with the same package name and the release SHA-1. If the app is installed from Google Play with Play App Signing, use the **App signing key** SHA-1 from the Play Console (Setup -> App signing), and also the upload key if you want sideloaded builds signed with it to work.

## 4. Try it

1. Install a debug APK on a phone with Google Play services and a screen lock.
2. Settings -> **Automatic backup** -> **Choose a PIN** (6 digits, or the advanced passphrase).
3. Switch on **Back up to Google Drive**. Google's own screen asks you to pick an account and agree to "See, create, and delete its own configuration data in your Google Drive". The account must be a test user (step 2.5).
4. The first backup runs at once. **Back up now** repeats it. In Google Drive on the web: Settings -> Manage apps shows the app with "Hidden app data"; the files themselves are not browsable (that is the point of `appDataFolder`).
5. To test a restore: uninstall the app, install again, and choose **Restore from Google Drive** on the Welcome screen.

## What leaves the phone

When (and only when) the switch is on: one file named `zyke-backup-<date>.zbk`, encrypted with a key made from your PIN, uploaded to the hidden app folder of the Google account you signed in with. The newest 7 are kept; older ones are deleted. Nothing else is sent, no account name or token is stored by the app, and there are no analytics. Turning the switch off stops uploads but leaves existing backups in your Drive (remove them from Google Drive settings -> Manage apps -> Zyke Message -> Delete hidden app data).

## Saving a copy to a folder (no Google account needed)

**Settings -> Backup -> Save a copy to a folder** is the third place, independent of the other two. Turning it on opens Android's folder picker once; the app keeps access to that one folder (`takePersistableUriPermission`, visible in the phone's own settings) and each scheduled backup also writes `zyke-backup-yyyyMMdd-HHmmss.zbk` there. The newest 7 files with exactly that name pattern are kept; nothing else in the folder is deleted. It needs no network, so its jobs never wait for Wi-Fi. The files are encrypted with your PIN: copy them anywhere, you need the PIN to open them.

The folder choice is stored in `backup_folder`, which is excluded from the setup backup and from Android's Auto Backup. After a reinstall or restore the setting is off and asks for a folder again. If the folder was deleted or the permission taken back, the backup status says so and offers **Pick the folder again**.

## Restoring from a file

**Restore from a file** (Settings -> Backup, and the Welcome step on a fresh install) opens any file you choose: a `.zbk` from the folder, a manual export, or the private `zyke-backup.bin`. Files over 50 MB are refused, and the start of the file is checked before the PIN or passphrase is asked ("This doesn't look like a Zyke backup"). It then follows the same path as every other restore.

## PIN strength

A 6-digit PIN has 1,000,000 possibilities. The key is stretched with 600,000 rounds of PBKDF2-HMAC-SHA256, which makes each guess slow, but anyone who obtains the backup file (from your Drive or from Android's backup) could still try all of them offline. It protects against casual access. For real protection use the **Advanced: use a passphrase instead** option with a long passphrase. Nobody, including the developer, can recover a forgotten PIN or passphrase.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Google sign-in was not completed" right away | The Android client is missing or has a different package name or SHA-1 than the APK; or the account is not a test user; or the client was created minutes ago. |
| "Sign in again" after a while | The permission expired or was revoked at <https://myaccount.google.com/permissions>. |
| Works in debug, not in a release build | The release key's SHA-1 has no client yet (step 3, release). |
| Nothing happens on a phone without Google Play services | The Authorization API needs Google Play services. Use the Android Auto Backup option instead. |
