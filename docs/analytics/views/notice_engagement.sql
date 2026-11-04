-- Per-notice reading: card attention, PDF opens and image views, keyed on the sender's log_id.
-- Apply: bq query --project_id=paramanu-seniors --use_legacy_sql=false < docs/analytics/views/notice_engagement.sql
--
-- 0.8.0 rows carry a per-device `notice_id` and no log_id, so they cannot be attributed and are
-- left out. Flings (< 500 ms) and rows over 10 minutes (a phone left foregrounded, or the shade
-- pulled over the list) are excluded from the medians but still counted in `impressions`.
CREATE OR REPLACE VIEW `paramanu-seniors.notices_analytics.notice_engagement` AS
WITH cards AS (
  SELECT
    log_id, user_pseudo_id, day,
    (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'position')   AS position,
    (SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'visible_ms') AS visible_ms,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'has_pdf')   = 1, FALSE) AS has_pdf,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'has_image') = 1, FALSE) AS has_image
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name = 'notice_card_visible' AND log_id IS NOT NULL
),
card_agg AS (
  SELECT
    log_id,
    COUNT(*)                                             AS impressions,
    COUNT(DISTINCT user_pseudo_id)                       AS devices,
    MIN(day)                                             AS first_seen,
    MAX(day)                                             AS last_seen,
    MIN(position)                                        AS best_position,
    LOGICAL_OR(has_pdf)                                  AS has_pdf,
    LOGICAL_OR(has_image)                                AS has_image,
    COUNTIF(visible_ms < 500)                            AS flings,
    APPROX_QUANTILES(IF(visible_ms BETWEEN 500 AND 600000, visible_ms, NULL), 100)[OFFSET(50)] AS p50_visible_ms,
    ROUND(SUM(LEAST(visible_ms, 600000)) / 1000.0, 1)   AS total_visible_s,
    COUNTIF(visible_ms >= 3000)                          AS impressions_over_3s
  FROM cards
  GROUP BY log_id
),
received AS (
  SELECT log_id, COUNT(DISTINCT user_pseudo_id) AS received_devices
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name = 'notice_received' AND log_id IS NOT NULL
  GROUP BY log_id
),
pdfs AS (
  SELECT
    log_id,
    COUNT(*)                                             AS pdf_opens,
    COUNT(DISTINCT user_pseudo_id)                       AS pdf_open_devices,
    COUNTIF(COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'was_cached') = 1, FALSE)) AS opens_from_cache,
    MAX((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'size_bytes')) AS pdf_size_bytes
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name = 'pdf_opened' AND log_id IS NOT NULL
  GROUP BY log_id
),
images AS (
  -- Durations are summed per device, never maxed: a rotation splits one viewing into several rows.
  SELECT
    log_id,
    COUNT(DISTINCT user_pseudo_id)                       AS image_view_devices,
    ROUND(SUM((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'duration_ms')) / 1000.0, 1) AS image_view_total_s
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name = 'image_viewer_closed' AND log_id IS NOT NULL
  GROUP BY log_id
)
SELECT
  c.log_id,
  STARTS_WITH(c.log_id, 'local-')                        AS is_local,
  TIMESTAMP_MILLIS(SAFE_CAST(c.log_id AS INT64))         AS sent_at,
  r.received_devices,
  c.* EXCEPT (log_id),
  IFNULL(p.pdf_opens, 0)          AS pdf_opens,
  IFNULL(p.pdf_open_devices, 0)   AS pdf_open_devices,
  IFNULL(p.opens_from_cache, 0)   AS opens_from_cache,
  p.pdf_size_bytes,
  IFNULL(i.image_view_devices, 0) AS image_view_devices,
  i.image_view_total_s,
  ROUND(SAFE_DIVIDE(c.devices, r.received_devices), 3)   AS seen_rate_by_device,
  ROUND(SAFE_DIVIDE(p.pdf_open_devices, c.devices), 3)   AS pdf_open_rate_by_device
FROM card_agg c
LEFT JOIN received r USING (log_id)
LEFT JOIN pdfs p     USING (log_id)
LEFT JOIN images i   USING (log_id)
ORDER BY sent_at DESC NULLS LAST, c.impressions DESC
