# Firebase setup (phone auth + reinstall tracking)

The app **builds and runs without Firebase** — all protection features (freeze,
app/website blocking, taper) work offline and never require sign-in. Firebase
only powers the optional phone sign-in and install/reinstall tracking.

To enable it you add your own `google-services.json`. When that file is present
at `app/google-services.json`, the build automatically applies the Google
Services plugin; when it is absent, the app just shows "Firebase not configured".

## 1. Create the Firebase project (from your phone's browser)
1. Go to <https://console.firebase.google.com> and sign in.
2. **Add project** → name it (e.g. "Fliptle") → continue (Analytics optional but
   recommended for the churn reporting).

## 2. Register the Android app
1. In the project, tap **Add app → Android**.
2. **Android package name:** `com.test.hello` (must match exactly).
3. Register, then **download `google-services.json`**.

## 3. Add the config to the repo
Commit the downloaded file to `app/google-services.json` (via the GitHub mobile
app or web upload). It contains client-side keys only; for production, restrict
them in Google Cloud console. On the next CI build the APK will be Firebase-enabled.

## 4. Enable the services in the Firebase console
- **Authentication → Sign-in method → Phone → Enable.**
  - For testing without real SMS, add a test number + code under Phone →
    *Phone numbers for testing*.
- **Firestore Database → Create database** (start in test mode for trials; add
  rules before production). Data is written to the `installs` collection.
- **Remote Config →** add a parameter:
  - key `reinstall_price_increase_threshold`, value `1` (change any time to move
    the reinstall threshold without rebuilding).

## 5. Build & install
Merge to `main`, download the APK from the Actions artifact, install, then use
**Sign in (phone)** on the main screen.
