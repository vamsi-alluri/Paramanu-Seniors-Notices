-- Per-day notice arrival and FCM delivery lag.
-- Apply: bq query --project_id=paramanu-seniors --use_legacy_sql=false < docs/analytics/views/notice_delivery.sql
--
-- Lag percentiles use only delivery_lag_ms >= 0. A negative lag means that handset's clock is
-- behind FCM's (design §9 trap 5); it is counted in clock_skew_receipts, not averaged in.
CREATE OR REPLACE VIEW `paramanu-seniors.notices_analytics.notice_delivery` AS
WITH r AS (
  SELECT
    day, user_pseudo_id, app_version, is_debug, log_id,
    (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'delivery_lag_ms')        AS delivery_lag_ms,
    (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'ms_since_process_start') AS ms_since_process_start,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'has_pdf')   = 1, FALSE) AS has_pdf,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'has_image') = 1, FALSE) AS has_image
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name = 'notice_received'
)
SELECT
  day,
  COUNT(*)                                                      AS receipts,
  COUNT(DISTINCT user_pseudo_id)                                AS devices,
  COUNT(DISTINCT log_id)                                        AS notices,
  COUNTIF(is_debug)                                             AS debug_receipts,
  COUNTIF(has_pdf)                                              AS with_pdf,
  COUNTIF(has_image)                                            AS with_image,
  APPROX_QUANTILES(IF(delivery_lag_ms >= 0, delivery_lag_ms, NULL), 100)[OFFSET(50)] AS p50_lag_ms,
  APPROX_QUANTILES(IF(delivery_lag_ms >= 0, delivery_lag_ms, NULL), 100)[OFFSET(90)] AS p90_lag_ms,
  MAX(delivery_lag_ms)                                          AS max_lag_ms,
  COUNTIF(delivery_lag_ms < 0)                                  AS clock_skew_receipts,
  COUNTIF(delivery_lag_ms IS NULL)                              AS no_sent_time_receipts,
  APPROX_QUANTILES(ms_since_process_start, 100)[OFFSET(50)]     AS p50_ms_since_process_start
FROM r
GROUP BY day
ORDER BY day
