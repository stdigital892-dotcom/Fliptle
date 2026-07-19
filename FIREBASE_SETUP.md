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
2. **Android package name:** `com.fliptle.app` (must match exactly).
   Also add the **debug SHA-1** fingerprint (printed in the GitHub Actions build
   log — see the "Print debug keystore SHA-1" step) so phone auth is authorized.
3. Register, then **download `google-services.json`**.

## 3. Add the config to the repo
Commit the downloaded file to `app/google-services.json` (via the GitHub mobile
app or web upload). It contains client-side keys only; for production, restrict
them in Google Cloud console. On the next CI build the APK will be Firebase-enabled.

## 4. Enable the services in the Firebase console
- **Authentication → Sign-in method** (all free, no billing):
  - **Email/Password → Enable.** Verification uses Firebase's built-in email
    link (free) — no extra setup.
  - **Google → Enable.** This populates the OAuth client so `default_web_client_id`
    is generated on the next build. You must also add your app's **SHA-1**
    (Project settings → your Android app → Add fingerprint) — the debug SHA-1 is
    printed in the GitHub Actions build log. Re-download `google-services.json`
    after enabling Google and commit it.
- **Firestore Database → Create database.** User records live in `installs/{uid}`
  (keyed by Auth UID); the parent phone is stored there as `parentPhone`
  (contact-only). Typing-gate attempts are written under
  `typing_gate/{uid}/attempts/{autoId}`.
- **Remote Config →** add a parameter:
  - key `reinstall_price_increase_threshold`, value `1` (change any time to move
    the reinstall threshold without rebuilding).

### Firestore Security Rules (server-side validation)
These make the **server** validate the typing gate: an attempt write is only
accepted if the value exactly equals the 1..50 sequence, so a wrong value is
rejected by Firestore itself.

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // A user may read/write only their own install record + events.
    match /installs/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
      match /events/{eventId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
    }
    // Typing gate: only accept the exact 1..50 sequence (server-side check).
    match /typing_gate/{uid}/attempts/{attemptId} {
      allow read: if request.auth != null && request.auth.uid == uid;
      allow create: if request.auth != null && request.auth.uid == uid
        && request.resource.data.value ==
           '1234567891011121314151617181920212223242526272829303132333435363738394041424344454647484950';
    }
  }
}
```

## 5. Build & install
Merge to `main`, download the APK from the Actions artifact, install, then use
**Sign in (phone)** on the main screen.
