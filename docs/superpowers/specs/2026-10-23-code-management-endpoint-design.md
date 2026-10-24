# `code_management`: moving the phone's database reads behind an endpoint

Status: design, not yet planned or built.
Date: 2026-10-23.
Branch: `code-management-endpoint`.

## Intent

Today the phone talks to Realtime Database directly. Two repositories do it:

- `ActivationRepository` writes a claim to `/codes/{CODE}` on redemption, and reads
  `/codes/{CODE}` on every verification for `usedBy`, `revoked`, `issued` and `dispensary`.
- `DispensaryRepository` reads `/dispensaries/{id}` for the dispensary name, the topic list
  that draws Settings, and the `info` node that draws the banner.

Both fire at three moments: activation, the app coming to the foreground
(`NoticeViewModel:327,356`), and `ActivationRefreshWorker` — daily, plus a jittered one-time
run when a notice lands on a device whose answer has aged out.

Every one of those opens a Realtime Database websocket. The Spark plan allows 100 simultaneous
across the whole estate, and the project is heading for roughly 400 devices against a single
dispensary. That ceiling is the binding constraint on this system and has already been hit once:
`verify()` used to run inside `onMessageReceived`, so a broadcast opened ~400 sockets in seconds
and the entitlement gate failed during the exact event it exists for. The fix moved it to a
jittered worker, which bought room without removing the ceiling.

`code_management` is an HTTPS endpoint that answers the phone's questions instead. HTTPS has no
connection ceiling. Once the phone's reads go through it, the handset stops being a database
client, and the ceiling stops being a design constraint on anything the phone does.

A second, smaller reason: a per-dispensary Remote Config sentinel — a revision marker the phone
checks before bothering to re-read a record that changes a few times a year — is only worth
having once the socket is gone. Today a dispensary read rides free on a connection `verify()`
has already opened, so a sentinel would save nothing. **The sentinel is explicitly parked and
depends on this work. It is not in scope here.**

## Goals

- The phone's activation write and its status read both go through `code_management` over HTTPS.
- The endpoint is the only thing the phone's own calls talk to; no handset opens a database
  socket for activation or verification.
- The claim stays as unforgeable as it is today: a phone can only ever claim a code for itself.
- Migration is gradual and reversible. Activation first, foreground second, each shippable alone.
- A failed or unreachable endpoint degrades to **cache only**: the device answers from what it
  last stored, reports `ActivationState.Unknown`, and keeps showing notices. It does **not** fall
  back to a direct database read — see the anti-goals.

## Non-goals

- **Staff operations stay exactly as they are.** Revoke, restore, and dispensary and banner edits
  continue to go from the Apps Script console straight to Realtime Database with the existing
  service account. They are not moving behind this endpoint, now or as part of this work.
- **The data does not move.** Realtime Database remains the store. The function sits above it and
  reads and writes it with admin credentials.
- **`database.rules.json` stays deployed and unchanged** for the duration. The direct client path
  keeps working while the migration runs; nothing is torn out ahead of the replacement.
- **The Remote Config sentinel is not built here.** See above.
- **Control messages do not change.** No control message triggers an HTTPS call — see below.
- Multi-dispensary support. The endpoint must not make it harder, but single-dispensary is what
  go-live needs and what this designs for.

## Anti-goals

Things that would make this a failure even if the endpoint worked:

- **A new thundering herd.** Replacing 400 simultaneous sockets with 400 simultaneous HTTPS
  requests would move the problem, not solve it. See the call triggers below — this is the single
  easiest way to get this wrong.
- **An endpoint that must be up for the app to work.** A notice reaching someone in their
  eighties must not depend on a function being warm. Unreachable means "carry on from cache",
  never "stop".
- **A runtime fallback to the direct database path.** Tempting, and wrong. If the endpoint is
  cold or down and ~400 devices fail over to Realtime Database at once, quirk 5.14 is restored
  exactly — through the mechanism built to remove it. Unreachable means cache, full stop.
- **A claim that can be taken from its rightful holder.** If the endpoint is weaker than the
  security rules it replaces, the migration has made things worse.
- **An outage nobody ever finds out about.** Because `Unknown` permits delivery, a completely
  dead endpoint has no user-visible symptom at all: every device drifts to `Unknown`, the gate
  permits everything, and the entitlement system has quietly stopped working. That is §5.14's
  failure in a new costume, except this time there is no connection ceiling leaving a trace.
  Something has to be able to notice — see below.
- **Something that needs minding.** After 1.0.0 this runs unattended. An endpoint that needs
  manual restarts, quota top-ups or credential rotation on a human schedule fails this.

## When the phone calls

**Exactly two triggers: app open, and the app coming to the foreground.**

Nothing else. In particular, **no control message triggers a call**, and this is deliberate.
`ControlMessage` has three types — `Revoke`, `Resume` and `Banner` — and all three are published
to the single control topic that *every* phone holds, revoked ones included. Each phone then
decides whether the message concerns it. So:

- A `Banner` edit reaches all ~400 devices. It already carries the html inside the payload, so
  no phone needs to ask anything. It keeps being applied locally by `InfoRepository.applyPushed`.
- A `Revoke` or `Resume` names one code. The other ~399 phones must not call on the strength of
  a message about somebody else.

Triggering on control messages would reproduce, against a cold-startable function, precisely the
burst that forced `verify()` off the message path. The message path stays free of network I/O.

`ActivationRefreshWorker` is left as an open question for the builder — see below.

## The contract

### Identity

The phone sends its **Firebase ID token**. The endpoint verifies it with the Firebase Admin SDK
and takes the uid **from the verified token**, never from the request body.

This is load-bearing and was decided explicitly. The current rules bind the claim to `auth.uid`,
which cannot be forged, so a phone can only ever claim a code for itself. A self-asserted uid in
a JSON body would drop that: codes are printed slips handed out in a dispensary, and anyone who
learned or guessed one could POST it with an invented uid and take the claim — the person holding
the slip would then be told their code was already used. Verifying the token restores exactly the
property the rules provide today.

Anonymous Firebase Auth is therefore kept. The phone already calls `getIdToken(true)` inside
`verify()`, so the token is not new work on the client.

Verifying the token also preserves the other thing that forced refresh buys today: a deleted or
disabled anonymous user is discovered, rather than a cached token passing forever.

### Activate

Phone sends: the ID token, and the code as typed.

The endpoint does what the security rules do today, and refuses for the same reasons:

- the code must exist;
- it must carry `issued` — a node without it was never printed by the NGO, which is what catches
  a self-minted code;
- it must not already carry a `usedBy`, unless that `usedBy` is this same uid, which is a
  harmless retry and must succeed;
- it must not be `revoked`.

On success it writes `usedBy` (the verified uid) and `activatedAt`, and answers with the same
body the status call returns, so the client has the dispensary in hand immediately and does not
need a second round trip. `issued`, `revoked`, `note` and `dispensary` are never modified.

The check-and-write must be atomic against a concurrent attempt on the same code. One code, one
device is a rule this project enforces deliberately: when somebody loses their phone the NGO
restores the existing code rather than issuing a new slip, and the ~400 printed codes are tracked
by hand through the `note` field.

**`note` must never be returned to the phone.** It carries holder details that are opt-in and
described to users as held "on request".

### Status

Phone sends: the ID token, and its stored code.

The endpoint answers with, at minimum: whether the claim is alive or revoked, and on alive, the
code record (minus `note`) and the full dispensary record — name, topics, and `info` including
the banner html and `htmlUpdated`.

The whole dispensary record rides along rather than being asked for separately. Once a round trip
is being made, the size of the body is not what costs anything.

The three outcomes the client already models must all remain expressible: active, revoked, and
**inconclusive**. Inconclusive is not a failure mode to be designed away — `ActivationState.Unknown`
permits delivery on purpose, because a user missing a closure notice over a weak signal is far
worse than an already-revoked device seeing one more public announcement. Every notice is
published on the website regardless; this gate is not access control and must never be mistaken
for it.

## Migration

Two steps, each shippable on its own, in this order:

1. **Activation.** `redeem()` calls the endpoint instead of writing to the database.
2. **Foreground.** `verify()` and the dispensary refresh collapse into the single status call.

Throughout, `database.rules.json` stays deployed as it is. Nothing is removed from the rules
until both steps are live and have been seen working on real devices.

There is no backward-compatibility burden inside closed testing. 0.8.0 is with roughly 14
testers who are fixed by hand, and 0.7.2 is what is in production on ~40 devices. Shims for
older builds are not wanted before go-live.

**Each step replaces its path outright rather than shipping both behind a flag.** "Reversible"
here means a step is small enough to be reverted by the next release, not that both
implementations ship together with a runtime switch. A switch would mean carrying and testing
two implementations of the activation path, and the fallback it implies is the anti-goal above.

**Whether this lands before or after the ~400 slips are handed out is not yet decided**, so the
plan cannot assume activation is a path almost nobody will take again. Step 1 gets full rollout
care either way.

## Instrumentation

**Every call to the endpoint is logged to analytics, outcome included — successes and failures
alike.** This is the outage signal the anti-goals demand, and it is a requirement, not a
suggestion.

It follows the taxonomy already in `AnalyticsEvent`: a closed set, free of Android and Firebase
imports, booleans as 1/0 longs, a null recorded by omitting the key. One new event, shaped like
`AttachmentFetchEnd` — the operation performed, the outcome, the duration, and the HTTP status
when there was one.

The outcome must distinguish the three cases the client already models, because collapsing them
destroys the signal's entire value:

- **active** / **revoked** — the endpoint answered.
- **inconclusive** — it did not: timeout, unreachable, a 5xx, an unverifiable token. This is the
  one that matters. A fleet-wide rise in inconclusive is what an outage looks like from the
  outside, and without it a dead endpoint and a healthy one produce identical telemetry.

Two limits to state plainly rather than discover later:

- **Analytics is behind the kill switch and off by default**, so this cannot be the only signal.
  A device that has never fetched Remote Config, or whose owner is in a cohort with collection
  off, reports nothing. A server-side complement — a Cloud Functions error-rate alert, or a
  heartbeat — is what covers the case where the endpoint is so broken that no client reaches it
  to report that fact.
- **The privacy policy has not caught up with what 0.8.0 already collects.** Adding a new event
  widens that gap. It does not block this work, but the published policy must describe this
  before go-live.

## Deployment

The function's source lives in this repository, in a new top-level directory. This would be the
repository's first CI of any kind — there are no workflows today.

**GitHub Actions, triggered by a push to `main` filtered to the function's path.** A merge that
touches the function deploys it. Nothing else does.

Be clear about what that means, because it was chosen deliberately over a manual gate: a merge
is a production change to the endpoint that every handset depends on, and there is no staging
deployment to catch it first. **Live is the only environment.** Two things follow, and the plan
owes both:

- **The workflow runs the function's tests before it deploys, and a failure stops the deploy.**
  With no gate and no staging, this is the only thing standing between a merge and ~400 devices.
- **A rollback has to be one step**, documented, and exercised at least once before go-live.
  Cloud Functions keeps previous revisions; the runbook should name the command, not describe
  the idea.

The client's cache-only degrade is what makes this survivable: a bad deploy makes devices report
`ActivationState.Unknown`, which permits delivery, so notices keep arriving while it is fixed.
That is a safety net, not a licence — it is also exactly the silent failure the anti-goals name,
which is why the outage signal is not optional.

**Authentication: Workload Identity Federation, not a service-account key.** The function's
runtime credential is keyless via Application Default Credentials; putting a downloadable JSON
key in GitHub Secrets to deploy it would reintroduce precisely the custody problem that choice
removed. WIF costs a one-time IAM setup and then there is no key anywhere — nothing in the repo,
nothing in Secrets, nothing to rotate.

Left to the builder: the workflow file itself, the WIF pool and provider setup commands, which
deploying principal and roles are granted, and how the function's tests are run in CI.

## After this lands

Not in scope, but the plan should not foreclose it:

- **The Remote Config sentinel**, per dispensary, parked until the socket is gone.
- **Closing `database.rules.json` to the client.** This is the intended end state and the point
  at which the security argument actually pays out. It needs a minimum-version story first,
  because shutting the rules bricks any handset still on an older build and a Play rollout cannot
  force the last few devices up. Waiting out the ~40 devices on 0.7.2 may be enough; a Remote
  Config kill or a forced-update screen are the alternatives. Separate work, with its own UX.

## Constraints

- **Cost.** The project is already on Blaze, so Cloud Functions are available and neither their
  creation nor their execution is gated. The constraint is not the plan, it is the bill: this
  must not cost anything. **Hard ceiling: 2 million executions per month**, the free grant.

  That is the number the client's call frequency has to be designed against. Across ~400 devices
  it allows roughly 165 calls per device per day, which is comfortable for app-open and
  foreground but is not unlimited — a status call fired on every resume, with no floor under it,
  can plausibly reach that on a phone someone picks up all day. See the rate-limiting item below.
- **Unattended.** After 1.0.0 nobody is minding this.
- **Latency on a weak connection.** The existing client budget is a 6 second timeout on the
  dispensary read and a similar bound on verification. A cold start must fit inside something
  the client can wait for — or the client must treat the wait as inconclusive and carry on.
- **The Apps Script quota is account-wide** and has run out before. The staff path is unchanged
  by this work, but the builder should not add Apps Script calls to it.
- **Unknown devices are the normal case.** Phones are offline, asleep, or never opened for days.
  Everything the endpoint returns is cached on the device and must draw instantly without it.

## Decisions already made

These were settled in conversation and are not the builder's to revisit:

- Phone-facing calls only; staff operations stay on the existing Apps Script path.
- The endpoint sits above Realtime Database; the data stays in Realtime Database.
- **It is a Cloud Function in the existing Firebase project.** The project is already on Blaze,
  so the reason to look elsewhere has gone. This also removes the cross-cloud hop to the database
  region, and removes credential custody entirely — Application Default Credentials, no exported
  service-account JSON, nothing to store and nothing to rotate.
- **Closing the phone's direct database access is the intended end state**, after both migration
  steps are live. It is not done as part of this work. See "After this lands".
- Identity is the anonymous Firebase uid, proven by a verified ID token, never a body field.
- Triggers are app open and foreground. No control message triggers a call.
- Activation migrates first, foreground second.
- Deployment is a GitHub Actions workflow on push to `main`, path-filtered, straight to the one
  live function. No manual gate, no staging environment.
- The rules and the direct client path stay live during the migration.
- The Remote Config sentinel is parked until this lands.

## Left to the builder

- **Runtime and region** for the Cloud Function. The host is settled; these are not. Region
  should sit with the database to keep the hop local.
- **How the phone learns the endpoint URL.** Remote Config is the obvious candidate given it is
  already in the app and already fetched — but it is a decision, with a bootstrapping question
  attached, and it should be made deliberately.
- **The exact request and response shapes**, status codes, and how "inconclusive" is distinguished
  from "revoked" on the wire. The distinction matters more than the encoding.
- **What `ActivationRefreshWorker` becomes.** It exists to keep `verify()` off the message path
  and to be the only route by which a restore reaches a device nobody opens. The second reason
  survives this work; the first may not. Whether the daily run calls the endpoint, and with what
  jitter, is open — but a dark device must still discover a restore.
- **Client-side rate limiting.** Foreground fires more often than it looks; the current code
  already leans on freshness checks (`ActivationFreshness`). The status call almost certainly
  needs its own floor given the 2M/month ceiling — what that floor is, and whether it reuses
  `ActivationFreshness` or gets its own, is open. The budget above is the thing to design to.
- **Abuse handling at the endpoint.** A verified token bounds who can call, but not how often.
- **Test strategy**, including how the endpoint is exercised without 400 real handsets.

## My calls, flagged

Settled while writing this up, as the author's judgment rather than the owner's:

- **Each step replaces its path outright**; no runtime flag carrying both implementations, and
  no fallback to the direct database read. Rationale: closed testing has no compat burden, and
  the fallback is the thundering-herd anti-goal.
- **Unreachable degrades to cache only**, reported as `ActivationState.Unknown`.
- **Every call is logged to analytics, failures included.** Now a stated requirement with its own
  section above, decided by the owner rather than by me.
- **The function's source lives in this repo**, in a new top-level directory. Deployment is
  automated — see below.
- The activate response returns the same body as status, rather than requiring a second call.
  Rationale: the client needs the dispensary immediately after redemption today, and does a
  second read to get it.
- A retry of activate by the uid that already holds the code succeeds rather than failing as
  "already used". Without this, any dropped response on a flaky connection bricks a slip.
- `note` is excluded from every response. It is opt-in holder data and the phone has no use
  for it.
