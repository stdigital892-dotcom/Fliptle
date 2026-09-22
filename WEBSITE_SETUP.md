# RESCUE campaign site — setup & Firestore structure

The marketing site is a single static file (`index.html`) served by GitHub Pages
at **fliptle.com**. It shares the **same Firebase project as the Android app**
(`apps-99e1e`) — no new project needed.

There are three pieces:

- **Phase 1 — Waitlist (LIVE):** email capture on `index.html` → Firestore `waitlist`.
  **Website visitors only** — pre-launch, no app yet, "early access" wording.
- **App sign-in → Firestore `appSignups` (LIVE):** the Android app writes here
  after every sign-in — a completely separate collection from `waitlist`. App
  users already have the app and are choosing a plan, so they get their own
  Cloud Function and email copy that never says "early access".
- **Welcome email → Offer page (LIVE):** two separate Cloud Functions in
  `functions/index.js`, one per collection above — `sendWaitlistWelcome`
  (waitlist → early-access copy) and `sendAppWelcomeEmail` (appSignups →
  "complete your setup" copy) — each emails a link to `offer.html`, which
  shows the app's features and three test-mode plans → Firestore
  `planSelections`.
- **Phase 2 — Subscriptions (HIDDEN):** plan + Razorpay/UPI checkout on
  `index.html` → Firestore `subscriptions`. Built but disabled behind a flag
  until you launch.

All tunables live in one `const RESCUE = { … }` block near the bottom of
`index.html`, plus the `firebaseConfig` in the Firebase `<script type="module">`.

---

## 1. Make Phase 1 actually save (required before launch)

The Firebase web config is already filled in with the project's public client
values. Firestore access is controlled by **Security Rules**, so you must add
rules that allow anonymous visitors to *create* (only) waitlist entries.

### 1a. (Recommended) Register a Web app
Firebase console → **Project settings** → **Your apps** → **Add app → Web** →
copy the generated `appId` into the `appId` field of `firebaseConfig` in
`index.html`. (Firestore works without it, but registering the web app also lets
you restrict the API key to your domain later.)

### 1b. Add these Firestore Security Rules
Firebase console → **Firestore Database → Rules**. Merge the blocks below into
your existing rules (keep the app's `installs` / `typing_gate` rules):

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // ---- keep your existing app rules (installs, typing_gate) above/below ----

    // Public waitlist: anyone may CREATE one entry; nobody can read/edit/delete.
    // WEBSITE visitors only — the app never writes here.
    match /waitlist/{docId} {
      allow create: if request.resource.data.email is string
                    && request.resource.data.email.matches('^[^@]+@[^@]+[.][^@]+$')
                    && request.resource.data.email.size() < 320;
      allow read, update, delete: if false;
    }

    // App sign-ins: an authenticated user may create/update/delete ONLY the
    // doc matching their own signed-in email (delete+recreate is how the
    // in-app "resend" link re-triggers sendAppWelcomeEmail). Separate from
    // `waitlist` above — this is the app's own group, not website visitors.
    match /appSignups/{docId} {
      allow read: if false;
      allow create, update, delete: if request.auth != null
                    && request.auth.token.email != null
                    && docId == request.auth.token.email.lower();
    }

    // Cosmetic public counter. Read by anyone; each write may only +1.
    match /stats/waitlist {
      allow read: if true;
      allow write: if request.resource.data.keys().hasOnly(['count'])
                   && request.resource.data.count is int;
    }

    // Subscriptions are written ONLY by your server after verifying the
    // Razorpay signature (see Phase 2). Clients cannot read or write them.
    match /subscriptions/{email} {
      allow read, write: if false;
    }

    // Test-mode plan selections from offer.html — no payment yet, so the
    // client writes directly. Anyone may CREATE one doc, keyed by their own
    // email, with a validated shape. No client can read, update or delete —
    // only the server (Admin SDK, which bypasses these rules) can read them.
    match /planSelections/{email} {
      allow read, update, delete: if false;
      allow create: if
        request.resource.data.email is string
        && request.resource.data.email == email
        && request.resource.data.email.matches('^[^@]+@[^@]+[.][^@]+$')
        && request.resource.data.selectedPlan is string
        && request.resource.data.selectedPlan in ['trial', 'monthly', 'annual']
        && request.resource.data.timestamp == request.time
        && request.resource.data.keys().hasOnly(['email', 'selectedPlan', 'timestamp', 'source']);
    }
  }
}
```

> During pre-launch testing you can temporarily loosen the `subscriptions` rule
> to `allow write: if true;` so the client-side fallback can write. **Do not
> ship that** — real payments must be verified server-side (Phase 2).

### 1c. Add your campaign video
In `index.html` → `RESCUE.CAMPAIGN_VIDEO_URL`, paste a **YouTube embed** URL, e.g.
`https://www.youtube.com/embed/XXXXXXXXXXX`. Empty = "coming soon" placeholder.

Optional: `RESCUE.HERO_IMAGE_URL` for an atmospheric hero image (kept dark under
an overlay so text stays readable); `RESCUE.WAITLIST_BASELINE` to add a starting
number to the displayed count.

---

## 2. Firestore data structure

### `waitlist` (collection) — Phase 1, WEBSITE visitors only
One auto-ID document per signup:

| field       | type      | example                          |
|-------------|-----------|-----------------------------------|
| `email`     | string    | `"user@example.com"` (lowercased)|
| `timestamp` | timestamp | server time (`serverTimestamp()`)|
| `createdAt` | string    | ISO string (client clock)        |
| `source`    | string    | `"website"`                      |
| `userAgent` | string    | browser UA                       |

`stats/waitlist` → `{ count: <int> }` — a single public counter document
incremented on each signup, used for the "N have already joined" signal.

**Export emails:** Firebase console → Firestore → `waitlist`, or
`gcloud firestore export`, or a scheduled function. (Reads are blocked from the
browser by design so the list can't be scraped.)

### `appSignups` (collection) — APP users only, separate from `waitlist`
Document ID = the signed-in user's **email** (lowercased). Written by the
Android app after every sign-in (`AppSignupHelper.kt`) — never by the website:

| field       | type      | example                              |
|-------------|-----------|----------------------------------------|
| `email`     | string    | `"user@example.com"` (lowercased)      |
| `signedAt`  | timestamp | server time (`serverTimestamp()`)      |
| `source`    | string    | `"app"`                                |

Triggers `sendAppWelcomeEmail`, which emails a "complete your setup / choose
your plan" link — this copy never says "early access", since the recipient
already has the app.

### `subscriptions` (collection) — Phase 2
Document ID = the subscriber's **email** (lowercased), so it's one record per
user and easy to look up:

| field               | type      | example                       |
|---------------------|-----------|-------------------------------|
| `email`             | string    | `"user@example.com"`          |
| `plan`              | string    | `"monthly"` / `"yearly"`      |
| `planName`          | string    | `"Monthly"`                   |
| `amount`            | number    | `199`                         |
| `currency`          | string    | `"INR"`                       |
| `status`            | string    | `"active"` / `"pending"` / `"cancelled"` |
| `razorpayPaymentId` | string    | `"pay_XX…"`                   |
| `razorpayOrderId`   | string    | `"order_XX…"`                 |
| `startedAt`         | timestamp | server time                   |
| `updatedAt`         | timestamp | server time                   |
| `source`            | string    | `"website"`                   |

> The Android app keys its own records under `installs/{uid}`. To link a paid
> subscription to an app user, look it up by `email` (or add the user's Auth
> `uid` to the subscription doc from your verify endpoint).

### `planSelections` (collection) — Offer page, test mode
Document ID = the user's **email** (lowercased) — one doc per user:

| field          | type      | example                              |
|----------------|-----------|---------------------------------------|
| `email`        | string    | `"user@example.com"` (lowercased)     |
| `selectedPlan` | string    | `"trial"` \| `"monthly"` \| `"annual"`|
| `timestamp`    | timestamp | server time (`serverTimestamp()`)     |
| `source`       | string    | `"web"` (or `"app"` if opened from a deep link) |

No payment is taken yet — this only records intent. **The client can create a
doc but never read, update, or delete one** (see rule above) — so a user's
first plan selection is final from the browser; picking a different plan on
a later visit will fail (it's an `update` in Firestore's eyes, which the rule
blocks). If you want changes allowed later, that's a deliberate rule tweak.

**Reading it back requires a server**, since clients have no read access:
- **Simplest:** read it with the **Admin SDK** (bypasses rules) from a Cloud
  Function or backend the app calls, e.g. an HTTPS function
  `getPlanSelection(email)` that returns `{ selectedPlan }`.
- **Or:** read it directly from the **Firebase console** for manual lookups.
- The Android app **cannot** call `.get()` on this collection with the client
  SDK — that request will be denied by the `allow read: if false` rule.

---

## 3. Phase 2 — enable subscriptions at launch

The checkout lives at the **hidden route `#access`** (e.g.
`https://fliptle.com/#access`). There is no link to it anywhere on the page.
While `RESCUE.LAUNCH_ENABLED = false`, visiting it shows a "Not open yet" panel.

To go live:

1. In `index.html` set `RESCUE.LAUNCH_ENABLED = true`.
2. Set `RESCUE.RAZORPAY_KEY_ID` to your Razorpay **Key ID** (`rzp_live_…`).
3. Adjust `RESCUE.PLANS` (names, `price` in ₹, `period`, `tagline`, `badge`).
4. **Stand up two serverless endpoints** (Firebase Cloud Functions, Cloudflare
   Workers, etc.) and put their URLs in `RESCUE.CREATE_ORDER_ENDPOINT` and
   `RESCUE.VERIFY_ENDPOINT`:
   - **Create order:** takes `{plan, amount, currency, email}`, calls Razorpay
     Orders API with your **Key Secret** (server-side only), returns `{id, amount}`.
   - **Verify + save:** takes Razorpay's `{razorpay_payment_id,
     razorpay_order_id, razorpay_signature, email, plan}`, verifies the HMAC
     signature with your Key Secret, then writes the `subscriptions/{email}` doc
     with Admin privileges.

   Why a server: the **Key Secret** must never be in the browser, and payment
   amount/signature must be verified server-side so a user can't forge a
   "paid" state. The client-side write in `index.html` is only a labelled
   test fallback for when no verify endpoint is set.

5. Keep the `subscriptions` rule as `allow read, write: if false;` — the Admin
   SDK in your verify endpoint bypasses rules, so clients never need write access.

**No-backend alternative:** if you don't want to run functions yet, use a
Razorpay **Payment Link / Payment Button** per plan and point the plan buttons
at those, then reconcile subscriptions from the Razorpay dashboard / webhook.

---

## 4. Feature-flag summary

| Flag | Location | Effect |
|------|----------|--------|
| `LAUNCH_ENABLED` | `RESCUE` in `index.html` | `false` = checkout route shows "Not open yet"; `true` = plans + payment |
| `CAMPAIGN_VIDEO_URL` | `RESCUE` | empty = placeholder; set = embeds the video |
| `RAZORPAY_KEY_ID` | `RESCUE` | required for payments |
| `CREATE_ORDER_ENDPOINT` / `VERIFY_ENDPOINT` | `RESCUE` | secure server order + verification |

Nothing about Phase 2 is visible or reachable by normal visitors until you flip
`LAUNCH_ENABLED` — the waitlist is the only active flow on `index.html`.

---

## 5. Welcome email → offer page (live)

Two separate Cloud Functions in `functions/index.js`, each watching its own
collection, each with its own email copy — never shared:

- **`sendWaitlistWelcome`** fires on every new `waitlist` doc (website
  visitors only) and emails `offer.html?email=<their email>&source=web` via
  Resend, with "early access" copy — these people don't have the app yet.
- **`sendAppWelcomeEmail`** fires on every new `appSignups` doc (written by
  the Android app on sign-in — see `AppSignupHelper.kt`) and emails
  `offer.html?email=<their email>&source=app`, with "complete your setup /
  choose your plan" copy — no "early access" wording, since these people
  already have the app.

Both point at the same `offer.html`; only the query string and the email
copy that got them there differ.

`offer.html`:
- Reads `?email=` and shows "Welcome, `<email>`" (falls back to a small inline
  email field if the param is missing, e.g. a client that strips query strings).
- Lists the app's core features (porn blocking, 3-day freeze, Shorts/Reels
  control, new-browser auto-block, accountability alerts — marked "Coming soon").
- Shows three plan cards — Free Trial (₹0/14 days), Monthly (₹99/mo), Annual
  (₹799/yr, "Save ₹389 · 33% off") — each with a **Choose this plan** button.
- **Free Trial** writes to `planSelections/{email}` and shows a confirmation.
- **Monthly / Annual** go through Razorpay Standard Checkout: see section 6.

Uses the same shared Firebase project/config as `index.html`.

---

## 6. Razorpay Standard Checkout on the offer page (live)

Live payments use two Cloud Functions in `functions/index.js`, both callable
(`onCall` v2, region `asia-south2` — must match Firestore), so the offer page
never has to touch the Razorpay Key Secret:

- **`createOrder`** — takes `{ email, plan }` (`plan` = `"monthly"` \| `"annual"`),
  looks up the amount from the server-side `PAID_PLANS` catalog (₹99 = 9900
  paise, ₹799 = 79900 paise), calls Razorpay's Orders API with Basic Auth
  (`KEY_ID:KEY_SECRET`), returns `{ orderId, amount, currency, keyId, planName }`.
  The `keyId` is public and is the only Razorpay identifier that ever reaches
  the browser. **Amounts come from the server, never the client.**
- **`verifyPayment`** — takes Razorpay's `{ razorpay_order_id,
  razorpay_payment_id, razorpay_signature }` plus `{ email, plan }`,
  recomputes `HMAC-SHA256(orderId + "|" + paymentId, KEY_SECRET)` in hex, and
  compares it against the signature in constant time (`crypto.timingSafeEqual`).
  If the signature matches, the Admin SDK writes `subscriptions/{email}` with
  `{ email, plan, planName, amount, currency, status: "active",
  razorpayPaymentId, razorpayOrderId, razorpaySignature, startedAt, updatedAt,
  source: "website" }`. If it doesn't match, the whole request is refused with
  `permission-denied` and **no write happens**.

### Set the Razorpay secrets
Both live in Google Secret Manager, never in code, never in the repo. From the
repo root in Git Bash / a terminal:

```
firebase functions:secrets:set RAZORPAY_KEY_ID
firebase functions:secrets:set RAZORPAY_KEY_SECRET
```

Each command prompts for a value with hidden input — paste the value from your
Razorpay dashboard (**Test mode → Settings → API Keys** while testing;
**Live mode → Settings → API Keys** at launch). The Key ID starts `rzp_test_`
in test mode and `rzp_live_` in production. To rotate a key later, run the
same command again — it just adds a new version.

Then redeploy so the functions pick up the new secrets:
```
firebase deploy --only functions
```

### Firestore rules
`subscriptions/{email}` stays fully locked to clients:
```
match /subscriptions/{email} {
  allow read, write: if false;
}
```
`verifyPayment` uses the Admin SDK, which bypasses these rules — clients
never need read or write access to `subscriptions`. If the app needs to check
a user's subscription status, add a callable like `getSubscription({email})`
in `functions/index.js` (server-side Admin SDK read) rather than opening
`subscriptions` reads to the client.

### What to test after deploying (test mode)

1. `firebase functions:list` — confirm `createOrder(asia-south2)` and
   `verifyPayment(asia-south2)` appear.
2. Load `https://fliptle.com/offer?email=test@example.com` in a browser.
3. Click **Monthly** → the Razorpay Checkout modal should open showing ₹99.
4. Pay with a Razorpay **test** instrument — e.g. card
   `4111 1111 1111 1111`, any future expiry, any CVV; or UPI id
   `success@razorpay`.
5. The page should show "You're subscribed to Monthly. Open the Fliptle app
   to continue."
6. Firebase Console → Firestore → `subscriptions/test@example.com` — one doc
   with `status: "active"`, correct `plan`, `amount`, and the Razorpay ids.
7. `firebase functions:log --only createOrder,verifyPayment` — you should see
   `Razorpay order created` then `Subscription activated`. No warnings.
8. **Failure paths worth testing:**
   - Dismiss the Razorpay modal → button re-enables, no message, no write.
   - Use UPI id `failure@razorpay` → `payment.failed` fires, no subscription
     is written.
   - Try tampering: in DevTools, override the `amount` returned by
     `createOrder` before opening the modal. The signature Razorpay produces
     won't match on the server, `verifyPayment` returns
     `permission-denied`, and no doc is written. (The Cloud Function logs
     `Razorpay signature mismatch` — that's the alarm to watch.)

### `razorpayWebhook` — server-to-server safety net

A third Cloud Function, `razorpayWebhook` (`onRequest` v2, region
`asia-south2`), covers the case `verifyPayment` can't: the app crashes or
loses connection right after a successful payment, before it ever calls
`verifyPayment`. Razorpay calls this webhook directly from its own servers on
`payment.captured`, independent of the client, so `subscriptions/{email}`
still gets written.

- Verifies Razorpay's `X-Razorpay-Signature` header — HMAC-SHA256 over the
  **raw** request body (`req.rawBody`, before JSON parsing) using a webhook
  secret, checked with `crypto.timingSafeEqual`. Rejects with `400` on any
  missing/invalid signature.
- Reads `email`/`plan` from `payment.notes` — the same `notes: { email, plan }`
  that `createOrder` already passes to the Razorpay Orders API, echoed back
  onto the payment entity.
- **Idempotent:** inside a Firestore transaction, skips the write if
  `subscriptions/{email}.razorpayPaymentId` already equals this payment's id
  and `status` is already `"active"` — so a Razorpay retry, or a race against
  `verifyPayment` writing the same payment, never double-writes.

**Register it:** Razorpay dashboard → **Settings → Webhooks → Add New
Webhook**. Active events: `payment.captured`. URL: the function's deployed
URL (`firebase deploy` prints it; format is
`https://asia-south2-<project-id>.cloudfunctions.net/razorpayWebhook`).
Razorpay generates a signing secret for the webhook at that point — that's a
**different secret from `RAZORPAY_KEY_SECRET`** — set it with:

```
firebase functions:secrets:set RAZORPAY_WEBHOOK_SECRET
```

then redeploy so the function picks it up.
