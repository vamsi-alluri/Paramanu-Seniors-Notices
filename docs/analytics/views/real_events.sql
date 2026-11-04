-- The base every other view reads: one row per GA4 event, dev and robo traffic removed.
-- Apply: bq query --project_id=paramanu-seniors --use_legacy_sql=false < docs/analytics/views/real_events.sql
CREATE OR REPLACE VIEW `paramanu-seniors.notices_analytics.real_events` AS
SELECT
  PARSE_DATE('%Y%m%d', event_date)                       AS day,
  TIMESTAMP_MICROS(event_timestamp)                      AS event_ts,
  event_name,
  user_pseudo_id,
  app_info.version                                       AS app_version,
  device.mobile_brand_name                               AS brand,
  device.mobile_model_name                               AS model,
  device.operating_system_version                        AS os_version,
  geo.country                                            AS country,
  geo.city                                               AS city,
  COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'debug_event') = 1, FALSE) AS is_debug,
  (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'ga_session_id')     AS ga_session_id,
  (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'ga_session_number') AS ga_session_number,
  -- The sender's notice id (epoch millis at send, or `local-*` for cards the app writes itself).
  -- The same on every phone, so rows from different devices join. 0.8.0 sent a per-device
  -- `notice_id` instead, which never compares across phones and is not carried here.
  (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'log_id')         AS log_id,
  event_params
FROM `paramanu-seniors.analytics_552268879.events_*`
-- Daily tables only. On Blaze an `events_intraday_YYYYMMDD` table can sit beside the finished
-- daily one for a while, and the wildcard would count that day twice.
WHERE REGEXP_CONTAINS(_TABLE_SUFFIX, r'^\d{8}$')
  -- 0.7.2: no analytics in it; its rows are development testing.
  -- 0.8.2, 0.8.3: development builds that never reached a track. 0.8.4: never uploaded (robo only).
  AND IFNULL(app_info.version, '') NOT IN ('0.7.2', '0.8.2', '0.8.3', '0.8.4')
  -- The development handset.
  AND IFNULL(device.mobile_model_name, '') != 'SM-S928U1'
  -- Google Play's pre-launch robo farm: Android 11 with no geo at all. It reported as IN2025 at
  -- first and with a NULL model since, so it is matched on OS + missing country, not the model.
  AND NOT (IFNULL(device.operating_system_version, '') = 'Android 11'
           AND IFNULL(geo.country, '') = '')
  AND NOT (IFNULL(device.mobile_model_name, '') = 'IN2025'
           AND IFNULL(device.operating_system_version, '') = 'Android 11')
