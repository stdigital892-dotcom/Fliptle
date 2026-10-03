# WhatsApp webhook (`whatsappWebhook`)

A Cloud Function (2nd gen, HTTP, region `asia-south2`) that Meta's WhatsApp Cloud
API calls. Code: `functions/whatsapp.js` (logic), `functions/phone.js` (number
helper), exported from `functions/index.js`.

## Deploy

Set the two secrets (each command prompts for the value):

```
firebase functions:secrets:set WHATSAPP_VERIFY_TOKEN
firebase functions:secrets:set WHATSAPP_APP_SECRET
```

- `WHATSAPP_VERIFY_TOKEN`: any random string you choose. Type the **same** string
  into Meta's webhook setup.
- `WHATSAPP_APP_SECRET`: the **App Secret** from Meta (App Dashboard -> App
  settings -> Basic). It signs every POST.

Deploy:

```
firebase deploy --only functions:whatsappWebhook
```

Function URL (the deploy output prints the definitive one):

```
https://asia-south2-apps-99e1e.cloudfunctions.net/whatsappWebhook
```

In Meta (WhatsApp -> Configuration -> Webhook): set the Callback URL to the URL
above, the Verify token to your `WHATSAPP_VERIFY_TOKEN`, click Verify and save,
then subscribe to the **messages** field.

## What it does

- **GET** - Meta's handshake: if `hub.mode` is `subscribe` and `hub.verify_token`
  matches, replies 200 with `hub.challenge` as plain text; otherwise 403.
- **POST** - checks `X-Hub-Signature-256` (HMAC-SHA256 of the **raw** body with the
  app secret); a missing or wrong signature gets 403 and nothing is written.
  Then it applies the payload and replies 200. If a write fails it replies 500 so
  Meta retries (every write is idempotent).
- **Opt-out** - a text reply of `STOP`, `STOP ALL` or `UNSUBSCRIBE` (case, extra
  spaces and trailing punctuation are ignored) records the sender as opted out.
  Not recognised: `STOPPED`, `please stop`, `stop all alerts`, non-text messages.
- **Status** - `sent`, `delivered`, `read`, `failed` are merged into
  `whatsappAlerts/{wamid}`.

## Data (server-only collections)

| Collection | Key | Fields |
|---|---|---|
| `whatsappOptOuts` | `optOutKey(number)` | `optedOut`, `optedOutAt`, `source`. Written once; a repeat STOP changes nothing. |
| `whatsappAlerts` | the WhatsApp message id (`wamid...`) | `waStatus`, `waStatusAt`, `waStatusUpdatedAt`, `waErrorCode` (failures only). |

Not stored: message text, the recipient number on status updates, Meta's error
text. Not logged: secrets, signatures, request bodies, phone numbers.

Opt-out documents hold a phone number. They are **not** removed when an account
is deleted (the deletion job only touches `installs`, `appSignups`,
`planSelections`, `subscriptions`, `waitlist`, `typing_gate` and
`deletionSweeps`), because the opt-out has to keep being honoured. Mention this in
the privacy policy.

## Rules for the future alert sender (it does not exist yet)

1. **Normalise every number with `optOutKey()` from `functions/phone.js`.** It turns
   `+91 98765 43210`, `098765 43210`, `9876543210` and `919876543210` into
   `919876543210`. Send to that form. Do not write another normaliser.
2. **Call `isOptedOut(db, number)` (from `functions/whatsapp.js`) before every
   send** and skip the send when it returns `true`. It also returns `true` for a
   value that can't be turned into a number (fails closed).
3. After sending, write `whatsappAlerts/{wamid}` with **merge**
   (`set(data, { merge: true })`) and **never** use the `wa*` field names. Status
   updates can arrive before that write; merging keeps them.

The Android app does not need to call the helper: it only stores the entered
parent number in `installs/{uid}.parentPhone` (`SignInActivity.normalizePhone`,
which keeps `+` and digits and does not produce the `91` form). The sender
normalises when it reads that field. If the app ever needs the canonical form
itself, port `phone.js` to Kotlin and check it against
`functions/test/phone-vectors.json`.

## Firestore rules

Clients must never read or write these collections. The Admin SDK ignores rules,
so denying clients does not affect the function. Add:

```
match /whatsappOptOuts/{id} { allow read, write: if false; }
match /whatsappAlerts/{id}  { allow read, write: if false; }
```

Also check the live rules for a catch-all such as `match /{document=**}` that
allows access, because it would open every collection that has no rule of its own.

## Tests

```
cd functions && npm run test:whatsapp
```
