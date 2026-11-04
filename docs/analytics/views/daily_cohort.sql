-- Per-day activity: devices, versions, how the app was opened, notification taps, metered share.
-- Apply: bq query --project_id=paramanu-seniors --use_legacy_sql=false < docs/analytics/views/daily_cohort.sql
CREATE OR REPLACE VIEW `paramanu-seniors.notices_analytics.daily_cohort` AS
WITH e AS (
  SELECT
    day, user_pseudo_id, event_name, app_version, is_debug,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'source') AS open_source,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'metered')    = 1, FALSE) AS metered,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'cold_start') = 1, FALSE) AS cold_start,
    (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'ms_since_shown') AS ms_since_shown
  FROM `paramanu-seniors.notices_analytics.real_events`
)
SELECT
  day,
  COUNT(DISTINCT user_pseudo_id)                                              AS active_devices,
  COUNT(DISTINCT IF(is_debug, user_pseudo_id, NULL))                          AS debug_devices,
  STRING_AGG(DISTINCT app_version, ', ' ORDER BY app_version)                 AS app_versions,
  COUNTIF(event_name = 'first_open')                                          AS first_opens,
  COUNTIF(event_name = 'session_start')                                       AS sessions,
  COUNTIF(event_name = 'app_open_source' AND open_source = 'launcher')        AS opens_launcher,
  COUNTIF(event_name = 'app_open_source' AND open_source = 'notification')    AS opens_notification,
  COUNTIF(event_name = 'app_open_source' AND open_source = 'link')            AS opens_link,
  COUNTIF(event_name = 'notification_tapped')                                 AS notification_taps,
  COUNTIF(event_name = 'notification_tapped' AND cold_start)                  AS taps_cold_start,
  APPROX_QUANTILES(IF(event_name = 'notification_tapped', ms_since_shown, NULL), 100)[OFFSET(50)] AS p50_tap_delay_ms,
  ROUND(SAFE_DIVIDE(COUNTIF(event_name = 'network_state_on_open' AND metered),
                    COUNTIF(event_name = 'network_state_on_open')), 3)        AS metered_share_on_open
FROM e
GROUP BY day
ORDER BY day
