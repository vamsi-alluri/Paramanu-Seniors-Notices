-- Per-day activation funnel: every RedeemResult and every offline cause has its own column.
-- Apply: bq query --project_id=paramanu-seniors --use_legacy_sql=false < docs/analytics/views/activation_funnel.sql
--
-- `shown` is a lower bound (design §9 trap 22: the first launch after install logs nothing), so
-- the rate is taken over `submitted`. Results come from ActivationState.analyticsName():
-- success, mistyped, notaccepted, samecode, offline. Offline causes come from RedeemPolicy:
-- no_uid, transport, timeout.
CREATE OR REPLACE VIEW `paramanu-seniors.notices_analytics.activation_funnel` AS
WITH a AS (
  SELECT
    day, user_pseudo_id, event_name, ga_session_id,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'result')        AS result,
    (SELECT p.value.string_value FROM UNNEST(event_params) p WHERE p.key = 'offline_cause') AS offline_cause,
    COALESCE((SELECT p.value.int_value FROM UNNEST(event_params) p WHERE p.key = 'has_code') = 1, FALSE) AS has_code
  FROM `paramanu-seniors.notices_analytics.real_events`
  WHERE event_name IN ('activation_shown', 'activation_submitted', 'activation_result')
)
SELECT
  day,
  COUNT(DISTINCT user_pseudo_id)                                        AS devices,
  COUNTIF(event_name = 'activation_shown')                              AS shown,
  COUNTIF(event_name = 'activation_shown' AND has_code)                 AS shown_with_code_prefilled,
  COUNTIF(event_name = 'activation_submitted')                          AS submitted,
  COUNTIF(event_name = 'activation_result' AND result = 'success')      AS succeeded,
  COUNTIF(event_name = 'activation_result' AND result = 'mistyped')     AS mistyped,
  COUNTIF(event_name = 'activation_result' AND result = 'notaccepted')  AS not_accepted,
  COUNTIF(event_name = 'activation_result' AND result = 'samecode')     AS same_code,
  COUNTIF(event_name = 'activation_result' AND result = 'offline')      AS offline,
  COUNTIF(event_name = 'activation_result' AND result = 'offline' AND offline_cause = 'timeout')   AS offline_timeout,
  COUNTIF(event_name = 'activation_result' AND result = 'offline' AND offline_cause = 'transport') AS offline_transport,
  COUNTIF(event_name = 'activation_result' AND result = 'offline' AND offline_cause = 'no_uid')    AS offline_no_uid,
  COUNT(DISTINCT IF(event_name = 'activation_result' AND result = 'success', user_pseudo_id, NULL)) AS devices_activated,
  ROUND(SAFE_DIVIDE(COUNTIF(event_name = 'activation_result' AND result = 'success'),
                    COUNTIF(event_name = 'activation_submitted')), 3)   AS success_rate
FROM a
GROUP BY day
ORDER BY day
