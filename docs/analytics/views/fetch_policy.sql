-- FetchPolicy decisions by attachment kind, network state and probe provenance.
-- Apply: bq query --project_id=paramanu-seniors --use_legacy_sql=false < docs/analytics/views/fetch_policy.sql
--
-- Rows are decisions, not attachments: a deferred attachment is re-decided on every later sweep
-- (design §9 trap 6). Leave probe = 'unreachable' out of any deferral ratio (trap 7).
CREATE OR REPLACE VIEW `paramanu-seniors.notices_analytics.fetch_policy` AS
WITH d AS (
  SELECT
    day, user_pseudo_id,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'kind')       AS kind,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'decision')   AS decision,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'probe')      AS probe,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'metered')    = 1, FALSE) AS metered,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'roaming')    = 1, FALSE) AS roaming,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'size_known') = 1, FALSE) AS size_known,
    (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'size_bytes')    AS size_bytes
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name = 'fetch_policy_decision'
)
SELECT
  kind, decision, metered, roaming, probe, size_known,
  COUNT(*)                       AS decisions,
  COUNT(DISTINCT user_pseudo_id) AS devices,
  COUNT(DISTINCT day)            AS days,
  MIN(size_bytes)                AS min_size_bytes,
  APPROX_QUANTILES(size_bytes, 100)[OFFSET(50)] AS p50_size_bytes,
  MAX(size_bytes)                AS max_size_bytes
FROM d
GROUP BY kind, decision, metered, roaming, probe, size_known
ORDER BY decisions DESC
