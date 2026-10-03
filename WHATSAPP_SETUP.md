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

### 0. Who the sender may send to: refuse everything else

The sender may send to a stored number **only if** one of these is true:

1. `normalizeIndianNumber(number)` (`functions/phone.js`) accepts it, meaning an
   Indian mobile in any spelling it understands (`+91 98765 43210`,
   `098765 43210`, `9876543210`, `919876543210`, ...); **or**
2. the number carries an **explicit `+` country code**: a `+`, then 8 to 15 digits
   in total, the first digit 1-9 (for example `+44 7911 123456`,
   `+1 415 555 2671`).

**Anything else must be refused**: no send, no guessing, no retry. That includes
every bare digit string that is not an Indian mobile (`4155552671`,
`14155552671`, `12345`), a number that is too short or too long, text, and an
empty value. Log the refusal without the number.

Why: a bare non-Indian digit string can't be tied to a country. Its opt-out key
would not match the number Meta reports when that person replies STOP, so the
opt-out would not be honoured. The `+` removes the doubt.

```js
const compact = String(stored).replace(/[\s().-]/g, "");
const ok =
  normalizeIndianNumber(compact) !== null || /^\+[1-9]\d{7,14}$/.test(compact);
if (!ok) return refuse("unsupported number format"); // never log the number
```

Notes:

- This rule is for numbers read from **our own data** (such as `parentPhone`). It
  does not apply to the `from` of an incoming STOP, which the webhook keys with
  `optOutKey()` directly, because Meta always sends the full international digits.
- Numbers the app has already stored may fail this rule. The profile-step
  parent-phone field (`SignInActivity.normalizePhone`) accepts any 7-15 digits.
  The sender refuses those, which is the intended fail-closed behaviour.

### 1-3. Every send

1. **Normalise every number with `optOutKey()` from `functions/phone.js`.** It turns
   `+91 98765 43210`, `098765 43210`, `9876543210` and `919876543210` into
   `919876543210`. Send to that form. Do not write another normaliser.
2. **Call `isOptedOut(db, number)` (from `functions/whatsapp.js`) before every
   send** and skip the send when it returns `true`. It also returns `true` for a
   value that can't be turned into a number (fails closed).
3. After sending, write `whatsappAlerts/{wamid}` with **merge**
   (`set(data, { merge: true })`) and **never** use the `wa*` field names. Status
   updates can arrive before that write; merging keeps them.

Order: rule 0 (acceptable number), then step 2 (opt-out check), then send to the
`optOutKey()` form, then step 3.

The Android app does not need to call the helper: it only stores the entered
parent number in `installs/{uid}.parentPhone` (`SignInActivity.normalizePhone`,
which keeps `+` and digits and does not produce the `91` form). The sender
normalises when it reads that field. If the app ever needs the canonical form
itself, port `phone.js` to Kotlin and check it against
`functions/test/phone-vectors.json`.

## Requirement for the partner-number screen (not built yet)

When the partner-number field is built in the app, it must accept **only**:

- exactly **10 digits, the first being 6-9** (an Indian mobile), after removing
  spaces, dashes, dots and brackets; **or**
- a **`+` followed by a country code and number** (8 to 15 digits in total, the
  first digit 1-9).

It must **reject every other bare digit string**, including:

| Entered | Result | Why |
|---|---|---|
| `9876543210` | accept | 10-digit Indian mobile |
| `+91 98765 43210` | accept | explicit `+` country code |
| `+44 7911 123456` | accept | explicit `+` country code |
| `09876543210` | reject | leading 0 |
| `919876543210` | reject | country code but no `+` |
| `4155552671`, `14155552671` | reject | foreign number without `+` |
| `5876543210` | reject | 10 digits but does not start 6-9 |
| `98765`, `98765432101` | reject | wrong length |
| `98765abc10` | reject | letters |

The screen is deliberately **stricter than the helper**. `phone.js` still accepts
older spellings (leading 0, `91` without `+`) so numbers stored earlier can be
read; new input must be unambiguous. Show an error that says what is accepted
("10-digit Indian mobile, or + and country code").

Recommended: store the accepted value in explicit form (`+91` plus the 10 digits,
or `+` plus the digits for another country) so the sender rule above holds without
any guessing. The Kotlin check should be tested against the table above.

The current profile-step parent-phone field does **not** meet this requirement. If
it will be used as a WhatsApp target it needs the same change.

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
