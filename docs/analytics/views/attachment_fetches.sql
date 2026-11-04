-- Attachment downloads by kind: outcome, HTTP status, duration and size.
-- Apply: bq query --project_id=paramanu-seniors --use_legacy_sql=false < docs/analytics/views/attachment_fetches.sql
--
-- outcome is only ever 'fetched' or 'failed'; a 4xx arrives as 'failed' with http_status set
-- (design §9 trap 18). A 'fetched' PDF with size_bytes = 0 is the shared .part race (trap 24).
CREATE OR REPLACE VIEW `paramanu-seniors.notices_analytics.attachment_fetches` AS
WITH f AS (
  SELECT
    day, user_pseudo_id, is_debug,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'kind')        AS kind,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'outcome')     AS outcome,
    (SELECT p.value.int_value    FROM UNNEST(event_params) p WHERE p.key = 'duration_ms') AS duration_ms,
    (SELECT p.value.int_value    FROM UNNEST(event_params) p WHERE p.key = 'http_status') AS http_status,
    (SELECT p.value.int_value    FROM UNNEST(event_params) p WHERE p.key = 'size_bytes')  AS size_bytes
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name = 'attachment_fetch_end'
)
SELECT
  kind,
  COUNT(*)                                                          AS fetches,
  COUNT(DISTINCT user_pseudo_id)                                    AS devices,
  COUNTIF(outcome = 'fetched')                                      AS fetched,
  COUNTIF(outcome = 'failed')                                       AS failed,
  ROUND(SAFE_DIVIDE(COUNTIF(outcome = 'failed'), COUNT(*)), 3)      AS fail_rate,
  COUNTIF(http_status IS NULL)                                      AS no_http_status,
  COUNTIF(http_status != 200)                                       AS non_200,
  COUNTIF(outcome = 'failed' AND http_status IS NULL)               AS failed_no_response,
  COUNTIF(outcome = 'failed' AND http_status = 200)                 AS failed_after_200,
  COUNTIF(outcome = 'fetched' AND size_bytes = 0)                   AS fetched_zero_bytes,
  APPROX_QUANTILES(IF(outcome = 'fetched', duration_ms, NULL), 100)[OFFSET(50)] AS p50_duration_ms,
  APPROX_QUANTILES(IF(outcome = 'fetched', duration_ms, NULL), 100)[OFFSET(90)] AS p90_duration_ms,
  MAX(duration_ms)                                                  AS max_duration_ms,
  APPROX_QUANTILES(NULLIF(size_bytes, 0), 100)[OFFSET(50)]          AS p50_size_bytes,
  MAX(size_bytes)                                                   AS max_size_bytes
FROM f
GROUP BY kind
ORDER BY fetches DESC
