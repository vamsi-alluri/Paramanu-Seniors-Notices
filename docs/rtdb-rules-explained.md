# Realtime Database rules — why they look like that

The rules themselves live in `database.rules.json` at the project root, as strict JSON with no
comments, because the console's rules editor is fussy about anything else. The reasoning lives here
instead.

**These rules are the validator.** There is no server of ours in the activation flow: the app writes
its own claim and the rules accept or refuse it. Running the app without deploying these rules
leaves the entire code list world-writable.

## Data shape

```
/codes/{CODE} = {
  issued:      1756600000000,   // written by the console when a slip is printed
  usedBy:      "<anonymous uid>",
  activatedAt: 1756600000000,   // server clock
  revoked:     true             // admin-only, set to cut a device off
}
```

The console writes `issued` (and later `revoked`) through the Admin SDK or the console itself, both
of which bypass rules entirely. Everything below governs the **app** only.

## Rule by rule

**`.read: false, .write: false` at the root** — nothing is readable or writable unless a rule below
grants it. Deny-by-default, so a node added later is closed until somebody thinks about it.

**`/codes/$code` has `.read: "auth != null"` but `/codes` does not.** This is the important one.
Reading a child requires no read permission on the parent, but *listing* children does. So a user
who already holds a code can verify it, and nobody can enumerate the code list. Guessing is the only
attack left: 32^7 payloads, roughly 34 billion, each needing its own network round trip. There is no
rate limiting available in RTDB rules — if that ever matters, the answer is longer codes, not more
rules.

**`.write: "auth != null && !data.child('usedBy').exists()"`** — claimable exactly once. Once
`usedBy` exists the node is closed to the app forever, which is also what stops a claimed code being
passed to somebody else and re-used. It is why a revoked code cannot be re-claimed either.

**`usedBy` must equal `auth.uid`** — a user can only claim a code *for themselves*, never on behalf
of another UID.

**`activatedAt` must equal `now`** — the server's clock, not the phone's. `ServerValue.TIMESTAMP`
resolves to `now` on write, so an honest client passes and a client sending its own timestamp fails.

**`issued` and `revoked` must be unchanged** (`newData.val() === data.val()`). The app writes with
`updateChildren`, so these children are present in the merged data and get validated; requiring them
to be identical to what is already stored means the app cannot forge an `issued` date or clear its
own `revoked` flag.

**`$other: { ".validate": false }`** — any child not named above is rejected, so the app cannot
stash arbitrary data under a code node.

## Verifying without the app

The console's **Rules Playground** settles the important property in two minutes:

1. Simulate a **write** to `/codes/TESTCODE`, authenticated, with
   `{"usedBy": "<some-uid>", "activatedAt": <now>}` — should be **allowed** when the node has no
   `usedBy`.
2. Run the same simulation again against a node that already has `usedBy` — should be **denied**.
3. Simulate a **read** of `/codes` — should be **denied** (no enumeration).
4. Simulate a **read** of `/codes/TESTCODE` — should be **allowed**.

## Deploying

Console: Build → Realtime Database → Rules → paste `database.rules.json` → Publish.

CLI (preferred, keeps the rules versioned next to the code that depends on them):

```
npm install -g firebase-tools
firebase login
firebase use --add          # pick paramanu-seniors
firebase deploy --only database
```

`firebase.json` at the project root already points at `database.rules.json`.

Publishing replaces whatever is live in one shot. If the database was created in test mode, the
current rules are `".read": true, ".write": true` with a 30-day expiry — which is exactly what you
want to overwrite, but look before you replace.


## Never import at the root of a live database

The console's **Import JSON** is a *replace*, not a merge, at whatever path is selected. Importing
at the root therefore deletes `/codes`, `/templates` and `/sent` along with everything else --
including the `usedBy` claims that installed phones depend on. This has already happened once.

**To change one subtree, select that node first.** Click into `/dispensaries/barc-vashi/info` in the data viewer, then
Import JSON with only the inner object:

```json
{ "heading": "Dispensary timings", "lines": "OPD: 9 am to 1 pm\nPharmacy: 9 am to 5 pm" }
```

Note the shape: `lines` is a **single newline-separated string**, not an array. `InfoRepository`
reads it with `getValue(String::class.java)`, which returns null for an array and silently leaves
the header hidden.

**Safer still, use the console.** Writes from Apps Script go through `firebase_('patch', ...)`,
which merges, so it cannot take out a neighbouring subtree by accident.

### If the root is wiped again

Phones store their code and anonymous UID locally, so a restored `/codes` node must carry the
original `usedBy` or the app will read the code as unclaimed, treat that as revoked, and reset
itself. Recover a device's UID from a debug build with:

```
adb shell run-as org.paramanuseniorshealth.notices cat shared_prefs/activation.xml
```

`/sent` is only a log and can be left empty. `/templates` cannot be recovered and has to be retyped.
