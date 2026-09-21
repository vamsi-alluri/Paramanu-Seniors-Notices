# Sender contract

What the sender must put on the wire for this app to work. Getting any of this wrong produces
symptoms that look like app bugs, so it is written down rather than assumed.

## The message

FCM HTTP v1, to the dispensary's notice topic: its first entry by `order` under
`/dispensaries/{DISPENSARY_ID}/topics` (for BARC Vashi, **`notices-v1`**).

```json
{
  "message": {
    "topic": "notices-v1",
    "android": { "priority": "high" },
    "data": {
      "logId":      "1788248869397",
      "topic":      "notices-v1",
      "title":      "Dispensary closed on Thursday 28 August",
      "body":       "OPD will reopen at 9 am on Friday.",
      "pdfUrl":     "https://paramanuseniorshealth.org/notices/2026-08-28.pdf",
      "pdfThumbUrl":"https://paramanuseniorshealth.org/notices/2026-08-28-thumb.jpg",
      "pdfPages":   "192",
      "pdfBytes":   "5872345"
    }
  }
}
```

### Rules, and why

**No `notification` block. Ever.** If one is present, the FCM SDK draws the tray notification
itself while the app is backgrounded, `onMessageReceived` never runs, and three things silently
stop happening: the entitlement gate is bypassed, nothing is written to history, and the PDF is
never rendered. This is the single most expensive mistake available here, and it presents as
"notifications work but the app is empty".

**`android.priority` must be `high`.** Data-only messages at normal priority are batched and
deferred in Doze, which for "closed tomorrow" defeats the point.

**Every value must be a string.** FCM v1 rejects non-string values in `data` and throws on
`undefined`. Omit a key rather than sending null.

**`title` is mandatory.** The app returns early without it. `body` may be blank but should not be:
the image is never allowed to be the whole message, because an A4 page in a notification is roughly
four-point text to a reader in their eighties.

**`topic` is mandatory, and must repeat the envelope's topic.** The app drops a notice without it.
It checks the value against what the phone's dispensary offers and what the user has switched on, and
takes the notification channel from that topic's `importance`. The envelope alone cannot do this: the
app is never told which topic a message arrived on.

**`logId` must be unique per notice, and should be epoch millis.** It is two things at once: the
idempotency key (Room holds a UNIQUE index on it and silently ignores a repeat, which is what makes
FCM's at-least-once delivery safe) and the notice's timestamp, so a phone that was switched off
overnight orders by when the NGO published rather than when it happened to receive.

A bare `Date.now()` is what the original Notifier sender used, and two sends inside the same
millisecond meant the second notice vanished with no error anywhere. The sender console therefore
allocates through `nextLogId_()`, which increments past any id already recorded under `/sent`. That
keeps both properties: unique, and still ordered. A UUID would be unique but would throw the
ordering away.

**`pdfUrl` is optional** and must be a plain, unauthenticated HTTPS URL to a PDF. Anything the app
cannot fetch or render costs only the picture — the notice still arrives as text.

**`pdfThumbUrl`, `pdfPages` and `pdfBytes` are optional, PDF-only, and travel together with
`pdfUrl`.** A real circular can run to hundreds of pages and several megabytes; fetching the
original on every one of four hundred phones for every notice is the problem these three keys
exist to avoid. In their place the app shows a small published thumbnail immediately and fetches
the full PDF only if the reader taps it.

| Key | Source in the feed | Notes |
|---|---|---|
| `pdfThumbUrl` | `media:thumbnail/@url` | A rendering of the PDF's first page, published as a JPEG. |
| `pdfPages` | `pn:pages` | A custom extension element — there is no standard RSS field for a page count. |
| `pdfBytes` | `enclosure/@length` | Already required by the RSS 2.0 spec for every enclosure; simply not discarded. |

All three are sent as **strings**, like every other `data` value, and omitted entirely rather than
sent empty, zero or `"NaN"` when the source is missing or unparsable — the app treats a
present-but-junk value differently from an absent one, so a sloppy passthrough is worse than no
value at all. None of the three is ever sent without `pdfUrl` also being present.

**Producing the thumbnail, page count and size is a CI step, not something Hugo does.** Hugo's own
image processing can resize and reformat images, but it cannot rasterize a PDF — its pipeline
accepts only formats it can already decode as an image, and a PDF is not one of them. The
thumbnail must therefore be produced *before* Hugo runs, as a build step, and dropped into
`assets/` alongside the PDF so Hugo can serve it like any other image:

```bash
pdftoppm -jpeg -r 100 -f 1 -l 1 circular.pdf thumb   # thumb-1.jpg: first page only, 100 dpi
pdfinfo circular.pdf | grep Pages                    # page count, for pn:pages
stat -c %s circular.pdf                               # byte size, for enclosure/@length
```

## Activation codes

The console writes a node per printed slip:

```
/codes/{CODE} = { "issued": 1756600000000, "dispensary": "barc-vashi" }
```

`CODE` is 8 characters of Crockford Base32 — alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, which
omits `I`, `L`, `O` and `U`. The eighth character is a position-weighted check character; generate
it with the same rule as `ActivationCode.checkCharacter`, or the app will reject the slip locally as
mistyped before it ever reaches the network.

The app claims a code by writing `usedBy` and `activatedAt` itself. The console must not write
those.

**To cut a device off:** set `/codes/{CODE}/revoked = true`. The console does this and queues a
revoke (below); a phone that misses the push discovers it at its daily check. Note this is
cooperative — the payload still reaches the device and the app declines to display it. Adequate
because every notice is public anyway; not access control.

**Reserve one code that is never handed out at the counter** and put it in the Play Console's *App
access* section. Without it a reviewer opens the app, meets the code screen, and rejects the
submission.

## Deployment trap inherited from the forked project

If the sender is Google Apps Script: `/exec` always serves the **deployed version**, never HEAD.
Every edit needs an explicit *New version* of the existing deployment. `testWebhook()` run from the
editor executes HEAD and therefore cannot detect a stale deployment — it will report success while
`/exec` serves months-old code. Echo a `SCRIPT_VERSION` constant in the response so one POST tells
you which code is live.


## Control messages

A second kind of message, sent by `pushControl_`, that manages the app rather than informing its
user. All go to **`control-v1`**, which every activated phone holds — revoked included — and which
never appears in Settings.

```json
{ "message": { "topic": "control-v1", "android": { "priority": "high" },
  "data": { "type": "revoke", "code": "A1B2C3D4", "at": "1789338553684" } } }
```

| `type`   | Other keys          | The phone                                                          |
|----------|---------------------|--------------------------------------------------------------------|
| `revoke` | `code`, `at`        | holding `code`: leaves the notice topics, keeps `control-v1`, shows the banner |
| `resume` | `code`, `at`        | holding `code` and revoked: rejoins the notice topics, clears the banner |
| `banner` | `dispensary`, `html`, `at` | of that dispensary, not revoked: replaces the cached banner; `html: ""` means removed |

No `title`, no `body`, and — as ever — no `notification` block. They show the user nothing. The app
checks for `type` before it looks for a title, because a payload with no title is otherwise dropped
as malformed. An unknown `type` is ignored, so new ones can be added without breaking older phones.

**`at` is epoch millis of the staff action**, as a string. FCM does not promise order, so a phone
ignores a revoke or resume stamped no newer than the last one it applied, and a banner stamped no
newer than the one it holds. Without it, a Disable and Enable seconds apart could land reversed and
leave the phone off. A message without `at` is ignored.

**The banner's html travels inside the message** rather than prompting phones to fetch it,
which would open four hundred database connections at once (SYSTEM.md §5.14). FCM refuses a data
payload over 4096 bytes, so the console refuses a banner over **3500 UTF-8 bytes**, and the drain
drops one that somehow exceeds it rather than retrying it forever.

These are **broadcasts**, not per-device addressing: every phone receives every revoke and resume
and compares the code with its own. That publishes the code to all of them, which is harmless, but
it is what goes out.

They are **not authoritative**. FCM is best-effort and a phone switched off past the message TTL
never sees them, so the device's own periodic verification remains the safety net, and a phone
rereads its dispensary when it comes to the foreground. See `docs/decisions.md`.

The console cannot send these itself, and has no HTTP route into the sender either — an OAuth-token
call returns 401, because `ScriptApp.getOAuthToken()` cannot authorize a call into another project's
web app. Instead the console writes `/controlQueue/code_{CODE}` or `/controlQueue/banner_{DISPENSARY}`,
and `Control.gs` drains them on a one-minute trigger, re-reading the code or the dispensary's banner
first so a request
that no longer applies is dropped rather than broadcast.

Delivery is at-least-once: a queue entry is cleared only once FCM has accepted the message, and only
if the console has not replaced it in the meantime. A device receiving the same message twice applies
it once.

## Who can send

Every function that reaches FCM calls `requireEditor_()` first, and the send paths call
`checkPin_()` after it. The web app is deployed **Execute as: User accessing** / **Who has access:
Anyone with a Google account**, so the URL alone reaches nothing: an unrecognised address is refused
before any payload is read.

The PIN is now a second factor rather than the only one, and can be switched off with the
`REQUIRE_STAFF_PIN` script property. It is kept on for the compose path, which sends arbitrary text
to four hundred phones.

**The PIN never reaches the browser.** It lives in Script Properties, the pages receive only a text
field, and nothing is written to `localStorage` or `sessionStorage`. Staff type it for each send.
That friction is the point: a PIN cached on a phone left face-up on a counter is a PIN anyone can
use.

**Guessing is capped.** Five wrong attempts locks the PIN for fifteen minutes, tracked in
`CacheService`. Cache entries can be evicted early, so this is best effort and not a substitute for
length — **use at least six digits, not four**.

**What this cannot protect against:** Script Properties are readable by anyone with edit access to
the Apps Script project. After alpha, that editor list *is* the list of people who can send, whether
or not they know the PIN. Narrowing it is a real access-control decision, not housekeeping.

Treat the web app URL as semi-secret even so. It is not a credential, but it is the thing an attacker
needs before the PIN matters at all.
