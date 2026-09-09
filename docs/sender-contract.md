# Sender contract

What the sender must put on the wire for this app to work. Getting any of this wrong produces
symptoms that look like app bugs, so it is written down rather than assumed.

## The message

FCM HTTP v1, to the topic named by `ActivationRepository.TOPIC` (currently **`notices-v1`**).

```json
{
  "message": {
    "topic": "notices-v1",
    "android": { "priority": "high" },
    "data": {
      "logId":  "b3f1c2a4-...",
      "title":  "Dispensary closed on Thursday 28 August",
      "body":   "OPD will reopen at 9 am on Friday.",
      "pdfUrl": "https://paramanuseniorshealth.org/notices/2026-08-28.pdf"
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

## Activation codes

The console writes a node per printed slip:

```
/codes/{CODE} = { "issued": 1756600000000 }
```

`CODE` is 8 characters of Crockford Base32 — alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, which
omits `I`, `L`, `O` and `U`. The eighth character is a position-weighted check character; generate
it with the same rule as `ActivationCode.checkCharacter`, or the app will reject the slip locally as
mistyped before it ever reaches the network.

The app claims a code by writing `usedBy` and `activatedAt` itself. The console must not write
those.

**To cut a device off:** set `/codes/{CODE}/revoked = true`. The app discovers this on the next
notice and returns to the code screen. Note this is cooperative — the payload still reaches the
device and the app declines to display it. Adequate because every notice is public anyway; not
access control.

**Reserve one code that is never handed out at the counter** and put it in the Play Console's *App
access* section. Without it a reviewer opens the app, meets the code screen, and rejects the
submission.

## Deployment trap inherited from the forked project

If the sender is Google Apps Script: `/exec` always serves the **deployed version**, never HEAD.
Every edit needs an explicit *New version* of the existing deployment. `testWebhook()` run from the
editor executes HEAD and therefore cannot detect a stale deployment — it will report success while
`/exec` serves months-old code. Echo a `SCRIPT_VERSION` constant in the response so one POST tells
you which code is live.


## The revoke envelope

A second kind of message, sent by `pushRevoke_` when the console revokes a code:

```json
{ "message": { "topic": "notices-v1", "android": { "priority": "high" },
  "data": { "type": "revoke", "code": "A1B2C3D4" } } }
```

No `title`, no `body`, and — as ever — no `notification` block. It shows the user nothing; it only
invalidates. The app checks for `type` before it looks for a title, because a payload with no title
is otherwise dropped as malformed.

This is a **broadcast**, not per-device addressing: the app is addressed only by topic, so every
subscribed phone receives every revoke and compares the code with its own. That publishes the
revoked code to all of them, which is harmless — a code carrying `revoked: true` is useless to
whoever reads it — but it is worth knowing that it is what goes out.

It is **not authoritative**. FCM is best-effort and a phone switched off past the message TTL never
sees it, so the device's own periodic verification remains the safety net. See `docs/decisions.md`.

The console cannot send this itself. It POSTs `{"action":"revoke","code":"…"}` to the sender's
`/exec` with the staff member's own OAuth token in the `Authorization` header, and the sender checks
that token against `ALLOWED_EDITORS` exactly as it does for every other entry point.

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
