# Staff Tracker (Android APK + Web dashboard)

Shift 10:00 AM - 6:00 PM. Employee registers, signs in, location starts automatically (auto check-in on first sign-in inside shift hours), auto stops at 6 PM.
Admin gets live map with names + trails, daily KM, dashboard counts, monthly report (CSV). Late = check-in after 10:15 AM.

## Update steps
1. GitHub: replace these 3 files in `app/src/main/java/com/stafftracker/app/` -> `MainActivity.kt`, `TrackService.kt`, and NEW `Duty.kt`. Commit -> Actions builds new APK.
2. Firebase -> Firestore -> Rules -> paste new `firestore.rules` -> Publish (required, rules changed).
3. Uninstall old app, install new APK. Register again OR keep old accounts (old accounts still work).

## Web URL (free)
1. Firebase console -> Project settings -> Your apps -> Add app -> Web (</>) -> copy the `firebaseConfig` values.
2. Open `web/index.html`, replace every "PASTE_HERE" with those values.
3. Create a NEW **public** GitHub repo (e.g. `stafftracker-web`), upload only `index.html`. (The config is not a secret; Firestore rules protect the data.)
4. Repo -> Settings -> Pages -> Source: Deploy from branch -> main -> / (root) -> Save.
5. URL: https://YOUR-USERNAME.github.io/stafftracker-web/
6. Firebase -> Authentication -> Settings -> Authorized domains -> Add domain `YOUR-USERNAME.github.io`.
7. Open URL, sign in with the admin account.

## Admin account
Register in app -> Firestore -> users -> your document -> role = admin.
