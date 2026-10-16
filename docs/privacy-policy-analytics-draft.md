# Privacy policy — analytics amendment draft

Draft replacement text for `docs/privacy-policy.md` (and the published copy on
paramanuseniorshealth.org). Each block says which existing text it replaces.

STATUS (2026-10-16): applied to `docs/privacy-policy.md`, with one deliberate difference —
block 7 was NOT applied as written. The per-device exclusion it promises was not built, so the
bullet now says only that one switch governs every phone. Blocks 6 and 8's retention and
region claims still depend on the console actions noted below. The published copy on the
website is not yet updated.

Written to match the existing register: short sentences, plain words, no jargon,
nothing a reader has to take on trust that could instead be stated plainly.

---

## 1. Header date

REPLACE:

    **Last updated: 2 September 2026**

WITH:

    **Last updated: 15 October 2026**

---

## 2. The "In short" paragraph

REPLACE the whole paragraph beginning "Paramanu Seniors Notices does not ask you for your name":

    Paramanu Seniors Notices does not ask you for your name, telephone number, email address,
    postal address, date of birth, or any health information, and it does not collect any of
    them. It has no advertising, no analytics, and no tracking. The notices it delivers are the
    same public announcements published on paramanuseniorshealth.org.

WITH:

    Paramanu Seniors Notices does not ask you for your name, telephone number, email address,
    postal address, date of birth, or any health information, and it does not collect any of
    them. It has no advertising and no advertising identifier, and nothing it collects is sold
    or shared for marketing. The notices it delivers are the same public announcements
    published on paramanuseniorshealth.org.

    The app does measure how it is being used — which notices are opened, how long pictures and
    documents take to arrive, and whether the app is opened from a notification. This is done to
    find and fix problems, and the measurements are not connected to your name or to any account
    of yours. The section "What the app measures" below describes exactly what is recorded.

---

## 3. New section: "What the app measures"

INSERT as a new section, immediately after "What the app sends over the internet"
and before "Who processes this information":

    ## What the app measures

    To find out whether the app is working properly on real phones, it records what happens as it
    is used, and sends those records to Google Analytics for Firebase. The records are tied to a
    random identifier created for this installation of the app. They are not tied to your name,
    your telephone number, your email address, your activation code, or any Google account.

    What is recorded:

    - That a notice arrived, and that the app was opened — including whether you opened it by
      tapping the notification or from your home screen.
    - Which notices were on screen and for how long, and when a picture or document was opened.
      Notices are recorded by their reference number only. The words of a notice are never sent.
    - How long pictures and documents took to arrive, how large they were, and whether they
      failed.
    - Whether the phone was on wifi or mobile data at the time, and whether it was roaming. The
      app does not record which network you are on, where you are, or your telephone number.
    - What happened when an activation code was entered: whether it was accepted, and if not, the
      reason. The code itself is sent only for the purpose described above, never as part of
      these measurements.
    - If the app stops unexpectedly, a technical report describing what went wrong.

    What is never recorded: your location, your contacts, your photographs, an advertising
    identifier, the text of any notice, or anything you type other than whether an activation
    code was accepted.

---

## 4. The "Nothing else is sent" paragraph

REPLACE:

    Nothing else is sent. In particular, the app does not send your location, your contacts, your
    photographs, an advertising identifier, or any record of what you read.

WITH:

    Apart from the measurements described in the next section, nothing else is sent. In
    particular, the app does not send your location, your contacts, your photographs, or an
    advertising identifier.

---

## 5. "What the app does not do" list

REPLACE the bullet:

    - No analytics or usage tracking of individuals.

WITH:

    - No tracking of you as a person. The usage measurements described above are tied to a random
      identifier for this installation, not to you, and no one at the organisation can work back
      from them to a named individual.

KEEP the other three bullets unchanged (advertising, no sale or sharing, no medical advice).

---

## 6. "How long it is kept"

APPEND to that section:

    Usage measurements are kept by Google Analytics for two months, which is the shortest period
    it allows. The detailed records used for analysis are deleted automatically after sixty days.
    Technical reports about unexpected stops are kept for ninety days.

NOTE FOR VAMSI — this paragraph makes three factual claims you must make true:
  - GA4 Admin → Data settings → Data retention → set to **2 months** (default) and
    turn OFF "Reset user data on new activity". The policy says two months; the
    setting must say two months.
  - Sixty days is the BigQuery sandbox table expiry — true automatically, no action.
  - Ninety days is Crashlytics' retention. Verify before publishing; if it differs,
    change the number here rather than leaving it approximately right.

---

## 7. "Your choices"

APPEND one bullet to that list:

    - **Measurement.** The usage measurement described above can be switched off for all users by
      the organisation, and is switched off when it is no longer needed. The app does not offer a
      per-person setting for it; if you would prefer your phone not to be included, contact the
      organisation using the details below and your installation will be excluded.

NOTE FOR VAMSI — this bullet promises a per-device exclusion you do not have yet.
Two honest options:
  (a) Build it: the kill-switch read already fetches a node from RTDB, so an
      exclusion list keyed by the activation code is a small addition.
  (b) Drop this bullet and say only that measurement can be switched off for
      everyone. Weaker for the reader, but true today.
  Do not publish (a)'s wording without (a)'s code.

---

## 8. "Who processes this information"

REPLACE:

    Google, through the Firebase service, on behalf of Paramanu Seniors Health. Messages are
    delivered by Firebase Cloud Messaging; the activation record is held in a Firebase database
    located in Singapore.

WITH:

    Google, through the Firebase service, on behalf of Paramanu Seniors Health. Messages are
    delivered by Firebase Cloud Messaging; the activation record is held in a Firebase database
    located in Singapore; usage measurements are processed by Google Analytics for Firebase and
    stored in Google BigQuery in Mumbai, India.

NOTE FOR VAMSI — "Mumbai, India" must match the dataset location you actually pick
in the BigQuery link wizard. If you end up on asia-southeast1, this says Singapore.

---

## Play Console Data Safety — the matching answers

| Question | Answer |
|---|---|
| App activity → App interactions | **Yes** — collected, not shared |
| App activity → Other actions | **Yes** if you keep the per-notice events |
| App info & performance → Crash logs | **Yes** — collected, not shared |
| App info & performance → Diagnostics | **Yes** — collected, not shared |
| Device or other IDs | **Yes** — the Analytics app-instance identifier |
| Advertising ID | **No** — unchanged, the manifest still strips it |
| Is the data linked to identity? | **No**, for every item above |
| Is collection required or optional? | **Required** — there is no in-app opt-out |
| Is data encrypted in transit? | **Yes** |
| Can users request deletion? | **Yes** — via the contact details in the policy |

The "Device or other IDs = Yes" line is the one most likely to be missed. The
GA4 app-instance id is a device identifier under Play's definition even though
it is random and unlinked, and under-declaring it is the kind of thing that gets
a listing flagged after the fact.
