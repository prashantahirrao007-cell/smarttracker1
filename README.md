# Staff Tracker (Android APK)

Employee login + admin login. Check In -> live location on map. Check Out -> tracking stops.
Admin sees all checked-in employees on a map (OpenStreetMap, free, no API key) with names, plus a report (CSV export).

## Setup (one time)
1. **Firebase (free Spark plan)**: https://console.firebase.google.com -> Add project.
   - Add Android app, package name **com.stafftracker.app** -> download `google-services.json` -> put it in the `app/` folder.
   - Build -> Authentication -> Get started -> enable **Email/Password**.
   - Build -> Firestore Database -> Create database. Then Rules tab -> paste the contents of `firestore.rules` -> Publish.
2. **GitHub**: create a **private** repository, upload this whole folder (including `app/google-services.json` and the hidden `.github` folder).
3. Repo -> **Actions** tab -> "Build APK" runs automatically (or press Run workflow). When green, open the run -> **Artifacts** -> download `StaffTracker-apk`, unzip -> `app-debug.apk`.
4. Install the APK on the phone (allow "install unknown apps").

## Make yourself admin
1. Open the app, **Register** with your name/email/password (everyone registers as employee).
2. Firebase console -> Firestore -> `users` -> your document -> change field `role` from `employee` to `admin`.
3. Log out and log in again -> you now see the admin map and Report.

## Notes
- Allow location when asked. For best tracking set the app battery mode to **Unrestricted**.
- Anyone with the APK can register as an employee. Keep the APK private; for tighter control, remove the Register button later and create users in Firebase console.
- This is a debug APK, fine for testing and internal use.
