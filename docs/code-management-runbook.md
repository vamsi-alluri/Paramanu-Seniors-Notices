# code_management runbook

The `code_management` Cloud Function (`functions/`) answers over HTTPS whether a patient's activation code is valid. About 400 devices depend on it. It deploys from `.github/workflows/deploy-function.yml` on every push to `main` that touches `functions/**`. There is no staging and no manual gate.

- GCP project: `paramanu-seniors`. Pass `--project paramanu-seniors` on every command; do not rely on a default.
- Function: `codeManagement` (export name), region `asia-southeast1`. As a 2nd-gen function it is a Cloud Run service named `codemanagement`.
- Repository: `vamsi-alluri/Paramanu-Seniors-Notices`.
- Scale: hard ceiling 2,000,000 executions/month; about 400 devices; the foreground status call has a 15-minute floor, so at most about 96 foreground calls per device per day (about 1.15M/month if every device were foregrounded around the clock, which never happens). On top of that: app opens, the daily worker, and an ungated "Check again" button.

## 1. Release ordering (do this in order)

Until the Remote Config key `code_management_base_url` is published, `CodeManagementConfig.isUsable("")` is false. Every status call returns `Inconclusive` without touching the network and `verify()` returns `Unknown`. The phone makes no call at all. Because this branch removed the Realtime Database fallback from both foreground callers, **the dispensary's topic list stops updating by any route**: a topic added or withdrawn in that window reaches nobody. The banner still arrives by control push. `ActivationRefreshWorker` also returns `Result.retry()` on every run throughout.

So the function and the key must exist before any build containing this branch ships:

- [ ] 1. Complete the one-time setup in section 2 (WIF, service account, roles).
- [ ] 2. Merge to `main`; confirm the workflow run is green and the `deploy` job ran.
- [ ] 3. Get the URL: `gcloud functions describe codeManagement --gen2 --region asia-southeast1 --project paramanu-seniors --format="value(serviceConfig.uri)"`.
- [ ] 4. Smoke test it: `curl -i -X POST "<url>/status"`. An unauthenticated call should be refused by the function itself with a 401 JSON body. A Cloud Run HTML 403 means the public invoker binding is missing; a 404 means the wrong URL. This only proves the function is reachable and public. It CANNOT catch a request-body bug: the function verifies the bearer token before it reads the body, so an unauthenticated probe never reaches the body parsing. The real gate is a call with a genuine ID token, which is the device walk: activate a code on a handset and confirm it comes back active, then confirm a status check answers.
- [ ] 5. Publish Remote Config key `code_management_base_url` = that https URL, no trailing slash. Publish the template.
- [ ] 6. Only now upload the app build (Play closed testing, then production).
- [ ] 7. After rollout, watch the GA4 queries in section 5 the next day.

Never unpublish or blank the key while builds containing this branch are in the field: that recreates the frozen-topic-list state above.

## 2. One-time setup: Workload Identity Federation (no keys)

No service-account JSON key is created, stored in the repo, or put in GitHub Secrets. Do not create one to "get unstuck".

```sh
PROJECT=paramanu-seniors
PROJECT_NUMBER=188591874425
REPO=vamsi-alluri/Paramanu-Seniors-Notices
SA=code-management-deployer

gcloud services enable iamcredentials.googleapis.com sts.googleapis.com \
  cloudfunctions.googleapis.com run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com eventarc.googleapis.com serviceusage.googleapis.com \
  --project $PROJECT

gcloud iam service-accounts create $SA \
  --display-name "code_management deployer (GitHub Actions)" --project $PROJECT

gcloud iam workload-identity-pools create github \
  --location global --display-name "GitHub Actions" --project $PROJECT

# The attribute condition is the security boundary. Without it, ANY GitHub
# repository could present a token to this provider and impersonate the
# service account. It pins the provider to this repository and to main.
gcloud iam workload-identity-pools providers create-oidc github-paramanu-notices \
  --location global --workload-identity-pool github \
  --issuer-uri "https://token.actions.githubusercontent.com" \
  --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
  --attribute-condition "assertion.repository == '$REPO' && assertion.ref == 'refs/heads/main'" \
  --project $PROJECT

# Let identities from that repository (and only it) act as the service account.
gcloud iam service-accounts add-iam-policy-binding \
  $SA@$PROJECT.iam.gserviceaccount.com \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/$REPO" \
  --project $PROJECT
```

The workflow's two identifiers (not secrets) are:

- `workload_identity_provider`: `projects/188591874425/locations/global/workloadIdentityPools/github/providers/github-paramanu-notices`
- `service_account`: `code-management-deployer@paramanu-seniors.iam.gserviceaccount.com`

### Roles for `code-management-deployer`

Exactly these, and no project-wide Editor, Owner or Firebase Admin:

```sh
SA_EMAIL=$SA@$PROJECT.iam.gserviceaccount.com
for ROLE in roles/cloudfunctions.developer roles/run.admin \
            roles/serviceusage.serviceUsageConsumer roles/firebase.viewer; do
  gcloud projects add-iam-policy-binding $PROJECT \
    --member "serviceAccount:$SA_EMAIL" --role $ROLE --condition=None
done

# Act as the function's runtime service account, on that account only, not project-wide.
# Find it with:
#   gcloud functions describe codeManagement --gen2 --region asia-southeast1 \
#     --project $PROJECT --format="value(serviceConfig.serviceAccountEmail)"
# On a first deploy it is the default compute service account.
RUNTIME_SA=<that email>
gcloud iam service-accounts add-iam-policy-binding $RUNTIME_SA \
  --member "serviceAccount:$SA_EMAIL" --role roles/iam.serviceAccountUser --project $PROJECT
```

| Role | Why |
|---|---|
| `roles/cloudfunctions.developer` | create and update the function |
| `roles/run.admin` | 2nd-gen functions are Cloud Run services; deploy sets the public invoker binding, which needs `setIamPolicy`. The broadest of the five; `run.developer` lacks that permission |
| `roles/serviceusage.serviceUsageConsumer` | the Firebase CLI checks which APIs are enabled |
| `roles/firebase.viewer` | the Firebase CLI reads the project |
| `roles/iam.serviceAccountUser` (on the runtime SA only) | deploying a function that runs as that account |
| `roles/artifactregistry.admin` (expected, unverified) | a gen2 deploy has firebase-tools create and set a cleanup policy on the `gcf-artifacts` repository, which needs `artifactregistry.repositories.update`; none of the roles above carries it. Add it with the same loop if the first deploy reports that permission |

The `principalSet` binding above is scoped to the repository only. `refs/heads/main` is enforced in one place, the provider's attribute condition, so loosening that condition silently widens the binding as well.

These roles were derived from what the Firebase CLI documents. They have not been exercised against the live project. If the first deploy fails with `PERMISSION_DENIED`, add the smallest role that grants the one named permission, record it here, and do not reach for a broad role. The deployer has no database, Remote Config or rules permissions, by design.

## 3. Rollback

A 2nd-gen function is a Cloud Run service; each deploy is a revision. Rolling back means sending 100% of traffic to the previous revision. It is instant and does not rebuild.

```sh
# 1. List revisions, newest first. Pick the last known-good one.
gcloud run revisions list --service codemanagement \
  --region asia-southeast1 --project paramanu-seniors

# 2. Send all traffic to it.
gcloud run services update-traffic codemanagement \
  --to-revisions <GOOD_REVISION_NAME>=100 \
  --region asia-southeast1 --project paramanu-seniors

# 3. Confirm.
gcloud run services describe codemanagement \
  --region asia-southeast1 --project paramanu-seniors \
  --format="value(status.traffic)"
```

Rollback is not finished at step 3. `--to-revisions NAME=100` clears the "latest revision" setting, so every later deploy builds a revision that receives no traffic. The pipeline goes green and the fix is not serving. Continue:

4. Revert or fix the commit on `main`, or the next unrelated `functions/**` push redeploys the bad code.
5. After the fix deploys, hand traffic back to the latest revision:
   `gcloud run services update-traffic codemanagement --to-latest --region asia-southeast1 --project paramanu-seniors`
6. Confirm traffic follows the newest revision, not the pinned one:
   `gcloud run services describe codemanagement --region asia-southeast1 --project paramanu-seniors --format="value(status.traffic)"`
   A green workflow run does not prove this.

**Status: this command sequence has not been exercised.** The service name `codemanagement` and the pinning behaviour after a later deploy are inferred. Exercise it once (section 6) and correct this section if anything differs.

## 4. Do not

- Create a service-account key, or add credentials to GitHub Secrets.
- Deploy database rules from this workflow. It deploys the function only; `database.rules.json` is separate.
- Add `continue-on-error` to the workflow.

## 5. Outage or silence? (GA4 in BigQuery)

Every call reports `code_management_call` with `operation` (`status` or `activate`), `outcome` (`active`, `revoked`, `refused`, `inconclusive`), `duration_ms`, and `http_status` (omitted when no response arrived). The export is `analytics_552268879`, a daily batch, so expect about a one-day lag; today's table does not exist yet. Dev and robo traffic mixes in, so every query below filters on `app_info.version = release_version`. The `DECLARE` default `0.8.1` is the `versionName` in `app/build.gradle` today; set it to the build you are asking about (it is a single exact match, so run once per version).

Daily outcome mix:

```sql
DECLARE release_version STRING DEFAULT '0.8.1';
SELECT
  PARSE_DATE('%Y%m%d', event_date) AS day,
  (SELECT value.string_value FROM UNNEST(event_params) WHERE key='operation') AS operation,
  (SELECT value.string_value FROM UNNEST(event_params) WHERE key='outcome')   AS outcome,
  COUNT(*) AS calls
FROM `paramanu-seniors.analytics_552268879.events_*`
WHERE _TABLE_SUFFIX BETWEEN FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 14 DAY))
                        AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
  AND app_info.version = release_version
  AND event_name = 'code_management_call'
GROUP BY day, operation, outcome
ORDER BY day DESC, operation, outcome;
```

Run with: `bq query --project_id paramanu-seniors --use_legacy_sql=false '<query>'`.

**Outage signature** (calls are happening but failing): the `inconclusive` share jumps, with 5xx or absent `http_status`.

```sql
DECLARE release_version STRING DEFAULT '0.8.1';
SELECT
  PARSE_DATE('%Y%m%d', event_date) AS day,
  COUNT(*) AS calls,
  COUNTIF((SELECT value.string_value FROM UNNEST(event_params) WHERE key='outcome') = 'inconclusive') AS inconclusive,
  COUNTIF((SELECT value.int_value FROM UNNEST(event_params) WHERE key='http_status') >= 500) AS http_5xx,
  COUNTIF((SELECT value.int_value FROM UNNEST(event_params) WHERE key='http_status') IS NULL) AS no_response,
  ROUND(SAFE_DIVIDE(
    COUNTIF((SELECT value.string_value FROM UNNEST(event_params) WHERE key='outcome') = 'inconclusive'),
    COUNT(*)), 3) AS inconclusive_share
FROM `paramanu-seniors.analytics_552268879.events_*`
WHERE _TABLE_SUFFIX BETWEEN FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 14 DAY))
                        AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
  AND app_info.version = release_version
  AND event_name = 'code_management_call'
GROUP BY day
ORDER BY day DESC;
```

A healthy day is mostly `active` with near-zero `inconclusive`. A high `inconclusive_share` with `http_5xx` > 0 is the function failing: read the Cloud Run logs and consider rollback. High `no_response` with no 5xx points at the phone side (no connectivity, unusable URL, token failure) or the service being unreachable.

A third shape is a malformed-request regression: 100% `inconclusive` with a 4xx `http_status` PRESENT (the function answers 400 to every call). It matches neither signature above, so do not read "no 5xx" as healthy. Add `COUNTIF(http_status BETWEEN 400 AND 499)` to the query when in doubt; a 4xx on `status` or `activate` is never normal traffic from the app, and the cause is the app and function disagreeing about the request. Roll back first, then investigate.

**Silence signature** (no events at all): compare call events with other app activity per day.

```sql
DECLARE release_version STRING DEFAULT '0.8.1';
SELECT
  PARSE_DATE('%Y%m%d', event_date) AS day,
  COUNTIF(event_name = 'code_management_call') AS cm_calls,
  COUNTIF(event_name = 'session_start')        AS sessions,
  COUNT(DISTINCT user_pseudo_id)               AS active_devices
FROM `paramanu-seniors.analytics_552268879.events_*`
WHERE _TABLE_SUFFIX BETWEEN FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 14 DAY))
                        AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
  AND app_info.version = release_version
GROUP BY day
ORDER BY day DESC;
```

- `cm_calls = 0` and `sessions = 0`: nobody opened the app. This is silence, not an outage, and is normal on quiet days.
- `cm_calls = 0` but `sessions > 0`: the app is running and not calling at all. That means an old build without this branch, or analytics switched off. It does NOT mean an unpublished key (see below).
- `cm_calls > 0` with a high `inconclusive_share`: outage, or an unpublished key. Run the next query to tell which.

**Unpublished or blank key looks like an outage.** The call path logs on every route, including the guard that refuses an unusable `code_management_base_url`. So with the key unpublished, devices emit a full volume of `code_management_call` events with `outcome = 'inconclusive'` and no `http_status`, exactly like an unreachable function. This is the frozen-topic-list state from section 1, so check it whenever the outage query lights up.

The discriminator is `duration_ms`. The unusable-URL guard returns before any token fetch or socket work, so those calls cluster at about 0-1 ms. A real transport failure, timeout or 5xx has measurable time in it.

```sql
DECLARE release_version STRING DEFAULT '0.8.1';
SELECT
  PARSE_DATE('%Y%m%d', event_date) AS day,
  COUNTIF(dur <= 5)  AS inconclusive_instant,
  COUNTIF(dur > 5)   AS inconclusive_with_time
FROM (
  SELECT
    event_date,
    (SELECT value.int_value FROM UNNEST(event_params) WHERE key='duration_ms') AS dur
  FROM `paramanu-seniors.analytics_552268879.events_*`
  WHERE _TABLE_SUFFIX BETWEEN FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 14 DAY))
                          AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
    AND app_info.version = release_version
    AND event_name = 'code_management_call'
    AND (SELECT value.string_value FROM UNNEST(event_params) WHERE key='outcome') = 'inconclusive'
    AND (SELECT value.int_value FROM UNNEST(event_params) WHERE key='http_status') IS NULL
)
GROUP BY day
ORDER BY day DESC;
```

A large `inconclusive_instant` count is a strong signal to go and check that the published Remote Config template actually carries `code_management_base_url`. It is not proof: a failure that returns immediately, such as no connectivity, can also be near zero. A large `inconclusive_with_time` count points at a real network or function problem. `duration_ms` also bundles a forced ID-token refresh with the network call, so it is not a clean endpoint latency.

## 6. Pipeline verification still outstanding (repo owner)

The workflow and this runbook were written without pushing to `main` or touching the live project. Before relying on them:

1. Complete section 2.
2. Push a trivial change under `functions/` to `main`. Confirm the `test` job runs both `npm test` and `npm run test:emulator`, that `deploy` runs only after both pass, and that the function updates.
3. Exercise the rollback in section 3 once and correct the commands if wrong.
4. Resolve the assumptions marked unverified above (service name, roles).
5. Create the outage alert and force a fault to see it fire (section 7). Not done.

## 7. Server-side outage alert (repo owner, manual)

Section 5 is client analytics: it sits behind a kill switch and is off by default, and it can only report what a client managed to send. The case it structurally cannot see is the endpoint being so broken that no client reaches it. This alert is the server-side counterpart and needs no client at all.

### What the function logs

Every `/status` and `/activate` outcome writes one structured entry (`route`, `outcome`, `httpStatus`, and `uid` or `reason` where they apply). Severity is the contract:

| Severity | Outcomes | Paged on? |
|---|---|---|
| INFO | `active`, `revoked`, `activated` | no |
| WARNING | 401 bad or missing token, 400 bad code, 409 refusal | no, this is expected traffic |
| ERROR | `fault` (an unexpected exception, answered 500) | yes |

The activation code, the ID token, the request body, the `note` field and error messages are never logged. A `fault` carries only the error's class name.

### Alert: `code_management server errors`

Fires when more than 3 ERROR-severity entries land in a 15 minute window, then closes itself after an hour of quiet. Cloud Run also writes its own ERROR request log for every 5xx, so one failed request is normally two entries; more than 3 therefore means at least two failed requests in 15 minutes, for a fault the app logs itself. Faults the app cannot log (crash on start, out of memory) leave only the Cloud Run request entry, one per request, so those need 4 failed requests in 15 minutes to page. With about 400 users opening the app a few times a day, a single isolated blip does not page, and a real outage shows up within minutes of the first couple of users.

Create it once. Every command names the project explicitly.

```bash
# 1. Notification channel: the project owner's email.
gcloud beta monitoring channels create \
  --project paramanu-seniors \
  --display-name="Owner email" \
  --type=email \
  --channel-labels=email_address=OWNER_EMAIL   # replace OWNER_EMAIL with your address; do not commit it
# Note the "name" it prints: projects/paramanu-seniors/notificationChannels/NNN

# 2. Log-based metric counting ERROR entries from the service.
gcloud logging metrics create code_management_server_errors \
  --project paramanu-seniors \
  --description="ERROR entries from the codemanagement Cloud Run service (server faults, 5xx)" \
  --log-filter='resource.type="cloud_run_revision" AND resource.labels.service_name="codemanagement" AND severity>=ERROR'

# 3. Alert policy on that metric. Replace CHANNEL with the name from step 1.
# Run from any scratch directory outside the repo; the file is deleted at the end.
cat > code-management-alert.json <<'JSON'
{
  "displayName": "code_management server errors",
  "combiner": "OR",
  "conditions": [{
    "displayName": "More than 3 server errors in 15 minutes",
    "conditionThreshold": {
      "filter": "metric.type=\"logging.googleapis.com/user/code_management_server_errors\" AND resource.type=\"cloud_run_revision\"",
      "aggregations": [{
        "alignmentPeriod": "900s",
        "perSeriesAligner": "ALIGN_SUM",
        "crossSeriesReducer": "REDUCE_SUM"
      }],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 3,
      "duration": "0s",
      "trigger": { "count": 1 }
    }
  }],
  "alertStrategy": { "autoClose": "3600s" },
  "notificationChannels": ["CHANNEL"]
}
JSON
gcloud alpha monitoring policies create \
  --project paramanu-seniors \
  --policy-from-file=code-management-alert.json
rm code-management-alert.json
```

The filter assumes the Cloud Run service is `codemanagement` (see section 3; still unverified). A log-based metric only counts entries written after it exists, so create it before the verification below.

**Verification not done.** This alert has not been created and has not been seen to fire: nothing is deployed yet. Once the function is live, force one fault against it (for example deploy a revision whose database URL is wrong, or temporarily make `statusFor` throw), call `/status` twice with a valid token, and confirm the email arrives within about 15 minutes. Then restore the revision (section 3). If the metric stays at zero, check that the service name in the filter matches section 3's `gcloud run services describe`.
