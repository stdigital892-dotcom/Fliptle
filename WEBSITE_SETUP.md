# RESCUE campaign site — setup & Firestore structure

The marketing site is a single static file (`index.html`) served by GitHub Pages
at **fliptle.com**. It shares the **same Firebase project as the Android app**
(`apps-99e1e`) — no new project needed.

There are two phases:

- **Phase 1 — Waitlist (LIVE):** email capture → Firestore `waitlist`.
- **Phase 2 — Subscriptions (HIDDEN):** plan + Razorpay/UPI checkout → Firestore
  `subscriptions`. Built but disabled behind a flag until you launch.

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
    match /waitlist/{docId} {
      allow create: if request.resource.data.email is string
                    && request.resource.data.email.matches('^[^@]+@[^@]+[.][^@]+$')
                    && request.resource.data.email.size() < 320;
      allow read, update, delete: if false;
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

### `waitlist` (collection) — Phase 1
One auto-ID document per signup:

| field       | type      | example                          |
|-------------|-----------|----------------------------------|
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
`LAUNCH_ENABLED` — the waitlist is the only active flow.
