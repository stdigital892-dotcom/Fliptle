# What Rescue's Accessibility service actually does

One accurate description, read from `UrlBlockAccessibilityService.kt` and
`accessibility_service_config.xml`, used verbatim as `a11y_service_description`
and as the basis for the in-app disclosure (`ob_a11y_body`) in `strings.xml`.
Keep all three in step; if the code's behaviour changes, update this file
first and then the two strings.

---

Rescue's Accessibility service has one job: blocking what you chose to block,
and nothing else.

**Apps watched.** Only web browsers (Chrome, Firefox, Edge, Samsung Internet
and others), Instagram and YouTube. No other app sends it anything.

**What it reads.**
- In a browser: the address bar. If it can't find the address bar by its
  known ID, it falls back to reading the visible text on the browser's
  screen, looking for something that looks like a web address or search
  term — this is the one case where it reads on-screen text beyond the
  address bar itself.
- In Instagram and YouTube: how the screen is built — which views are
  present and how they're arranged — not your feed's content, not any text
  or images in it. This is only ever used to recognise the shape of a
  full-screen Reels, Stories or Shorts player.

**What it does, on a match.**
- Shows a block screen over the page.
- Presses the phone's Back action to leave it; if the same page reappears
  right away, presses Home instead.
- For a blocked search, opens a safe-search address in place of the one you
  searched, instead of just leaving the page.
- For an adult-content or custom-domain match, or a blocked search word,
  blocking only applies once the user has turned on adult-content blocking;
  the user's own domain list always applies.
- For Reels, Stories or Shorts (when that toggle is on): shows the block
  screen and leaves the player the same way.

**What stays private.** Every check happens on the phone, against the
block list stored on the phone. Nothing it reads — the address, the
screen's structure, or anything else — is stored or sent anywhere, by Rescue
or to anyone else.

**What it can't do.** This service (and nothing else in Rescue) can prevent
uninstalling the app, clearing its data, or force-stopping it. Turning
Accessibility off (in Android's own settings) stops this feature
completely; Rescue can't protect you without it.

---

## What the old texts left out

Before this rewrite, `a11y_service_description` and `ob_a11y_body` did not
mention:
- the service pressing **Back** (and escalating to **Home**) to leave a
  blocked page — the only on-screen *action* it takes beyond showing an
  overlay;
- opening a **safe-search redirect** address in place of a blocked search;
- that it blocks **Reels, Stories and Shorts** the same way (shown
  elsewhere in the app, but not stated here);
- that Rescue **cannot prevent uninstalling, clearing data, or
  force-stopping** the app — a claim this review found missing everywhere
  the service was described.

## "No screen content is read" — can that still be said truthfully?

**No.** The screen-text fallback (`scanForUrl` in
`UrlBlockAccessibilityService.kt`) reads visible text nodes on the browser's
screen — not just the address bar — whenever the address bar can't be found
by its known ID. The surviving, truthful claim is narrower: nothing it reads
is **stored or sent anywhere**, and in Instagram/YouTube it reads the
screen's *structure*, never feed content, images or text. The disclosure
above states the fallback plainly instead of implying no screen text is
ever read.
