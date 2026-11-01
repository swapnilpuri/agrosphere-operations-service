-- Internal generated-only review candidate; NOT a Flyway migration or runtime installation.
-- Install only after reviewing this exact file in a separately owned disposable schema.
-- The schema is captured once by the installer, never supplied by a caller of the function.
DO $install$
DECLARE s text := current_schema();
BEGIN
 IF s IS NULL OR s IN ('pg_catalog','information_schema') OR s LIKE 'pg_temp%%' THEN
  RAISE EXCEPTION 'Explicit owned service schema required';
 END IF;
 EXECUTE format($definition$
 CREATE FUNCTION %1$I.privacy_observe() RETURNS jsonb
 LANGUAGE sql STABLE SECURITY DEFINER
 SET search_path = pg_catalog, pg_temp
 SET timezone = 'UTC'
 AS $body$
 WITH clock AS MATERIALIZED (SELECT statement_timestamp() AS at),
 eligible AS MATERIALIZED (
  SELECT x.alert_id, 'read'::text AS kind,
   CASE WHEN x.attention_version<=a.read_expired_through THEN NULL
    ELSE least(CASE WHEN a.inactive_since+interval '30 days'<=t.at THEN a.inactive_since+interval '30 days' END,
               CASE WHEN x.read_at<=m.key_cutoff THEN m.requested_at END) END AS due
  FROM %1$I.privacy_read x JOIN %1$I.privacy_alert a ON a.id=x.alert_id CROSS JOIN clock t
  LEFT JOIN %1$I.privacy_removal m ON m.issuer=x.issuer AND m.subject=x.subject
  WHERE x.attention_version<=a.read_expired_through OR a.inactive_since+interval '30 days'<=t.at OR x.read_at<=m.key_cutoff
  UNION ALL
  SELECT c.alert_id, 'receipt',
   CASE WHEN c.attention_version<=a.read_expired_through THEN NULL
    ELSE least(CASE WHEN c.expires_at<=t.at THEN c.expires_at END,
               CASE WHEN a.inactive_since+interval '30 days'<=t.at THEN a.inactive_since+interval '30 days' END,
               CASE WHEN c.completed_at<=m.key_cutoff THEN m.requested_at END) END
  FROM %1$I.privacy_command c JOIN %1$I.privacy_alert a ON a.id=c.alert_id CROSS JOIN clock t
  LEFT JOIN %1$I.privacy_removal m ON m.issuer=c.issuer AND m.subject=c.subject
  WHERE c.expires_at<=t.at OR c.attention_version<=a.read_expired_through OR
        a.inactive_since+interval '30 days'<=t.at OR c.completed_at<=m.key_cutoff
  UNION ALL
  SELECT e.alert_id, 'evidence',
   CASE WHEN e.alert_version<=a.history_expired_through THEN NULL ELSE a.inactive_since+interval '365 days' END
  FROM %1$I.privacy_evidence e JOIN %1$I.privacy_alert a ON a.id=e.alert_id CROSS JOIN clock t
  WHERE e.alert_version<=a.history_expired_through OR a.inactive_since+interval '365 days'<=t.at
  UNION ALL
  SELECT x.alert_id, 'ack',
   CASE WHEN x.attention_version<=a.read_expired_through AND a.last_history_expired_at IS NOT NULL
      AND NOT EXISTS(SELECT 1 FROM %1$I.privacy_evidence e WHERE e.alert_id=a.id AND e.attention_version=x.attention_version
       AND e.alert_version>a.history_expired_through
       AND (a.inactive_since IS NULL OR a.inactive_since+interval '365 days'>t.at))
    THEN NULL ELSE a.inactive_since+interval '365 days' END
  FROM %1$I.privacy_ack x JOIN %1$I.privacy_alert a ON a.id=x.alert_id CROSS JOIN clock t
  WHERE a.inactive_since+interval '365 days'<=t.at OR
   (x.attention_version<=a.read_expired_through AND a.last_history_expired_at IS NOT NULL
    AND NOT EXISTS(SELECT 1 FROM %1$I.privacy_evidence e WHERE e.alert_id=a.id AND e.attention_version=x.attention_version
     AND e.alert_version>a.history_expired_through
     AND (a.inactive_since IS NULL OR a.inactive_since+interval '365 days'>t.at)))
  UNION ALL
  SELECT NULL::uuid, 'ledger', m.expires_at FROM %1$I.privacy_removal m CROSS JOIN clock t WHERE m.expires_at<=t.at
 ), classes AS (
  SELECT k.kind, count(e.kind) AS n,
   CASE WHEN count(e.kind)=count(e.due) THEN min(e.due) END AS oldest
  FROM (VALUES ('read'),('receipt'),('ack'),('evidence'),('ledger')) k(kind)
  LEFT JOIN eligible e ON e.kind=k.kind GROUP BY k.kind
 )
 SELECT jsonb_build_object(
  'version',1,'observerRevision','AP_OBSERVER_CANDIDATE_1',
  'observedAt',to_char(t.at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'eligibleRootCount',(SELECT count(DISTINCT alert_id) FROM eligible),
  'copies',(SELECT jsonb_object_agg(kind,jsonb_build_object('count',n,'oldestEligibleAt',
     to_char(oldest AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))) FROM classes),
  'pendingRemoval',(SELECT jsonb_build_object('count',count(*),'oldestRequestedAt',
     to_char(min(requested_at) AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
    FROM %1$I.privacy_removal WHERE completed_at IS NULL)) FROM clock t;
 $body$
 $definition$,s);
 EXECUTE format('REVOKE ALL ON FUNCTION %I.privacy_observe() FROM PUBLIC',s);
END
$install$;
