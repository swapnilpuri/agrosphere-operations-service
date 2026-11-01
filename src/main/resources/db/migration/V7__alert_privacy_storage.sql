-- AP: new privacy storage only. No existing table/guard/grant/row is altered.
CREATE TABLE privacy_alert (
 id uuid PRIMARY KEY, incident_id uuid NOT NULL UNIQUE REFERENCES crop_health_incident(id),
 estate_id uuid NOT NULL, block_id uuid NOT NULL, source_version bigint NOT NULL CHECK(source_version>=0),
 alert_version bigint NOT NULL CHECK(alert_version>=1), attention_version bigint NOT NULL CHECK(attention_version BETWEEN 1 AND alert_version),
 state varchar(8) NOT NULL CHECK(state IN ('ACTIVE','INACTIVE')), acknowledged boolean NOT NULL,
 attention_at timestamptz NOT NULL, inactive_since timestamptz, last_history_expired_at timestamptz,
 read_expired_through bigint NOT NULL DEFAULT 0, history_expired_through bigint NOT NULL DEFAULT 0,
 body jsonb NOT NULL,
 CHECK((state='ACTIVE')=(inactive_since IS NULL)),
 CHECK(body @> jsonb_build_object('id',id,'incidentId',incident_id,'estateId',estate_id,'blockId',block_id,'sourceVersion',source_version,'alertVersion',alert_version,'attentionVersion',attention_version,'state',state,'acknowledged',acknowledged,'dataProvenance','SIMULATED')),
 CHECK(NOT body ?| ARRAY['issuer','subject','acknowledgedBy','acknowledgedAt','readAt','readAttentionVersion','correlationId']),
 CHECK(body - ARRAY['id','incidentId','estateId','blockId','sourceVersion','alertVersion','attentionVersion','state','acknowledged','dataProvenance','ruleVersion','inactiveReason','category','severity','incidentStatus','estateTimezone','observedAt','raisedAt','attentionAt','updatedAt']='{}'::jsonb)
);
CREATE TABLE privacy_read (
 alert_id uuid NOT NULL REFERENCES privacy_alert(id), issuer varchar(2048) NOT NULL, subject varchar(200) NOT NULL,
 attention_version bigint NOT NULL CHECK(attention_version>=1), read_at timestamptz NOT NULL,
 PRIMARY KEY(alert_id,issuer,subject), CHECK(length(issuer)>0 AND length(subject)>0)
);
CREATE TABLE privacy_ack (
 alert_id uuid NOT NULL REFERENCES privacy_alert(id), attention_version bigint NOT NULL CHECK(attention_version>=1),
 issuer varchar(2048) NOT NULL, subject varchar(200) NOT NULL, acknowledged_at timestamptz NOT NULL,
 PRIMARY KEY(alert_id,attention_version), CHECK(length(issuer)>0 AND length(subject)>0)
);
CREATE TABLE privacy_evidence (
 alert_id uuid NOT NULL REFERENCES privacy_alert(id), alert_version bigint NOT NULL CHECK(alert_version>=1),
 attention_version bigint NOT NULL CHECK(attention_version>=1), kind varchar(24) NOT NULL CHECK(kind IN('RAISED','SOURCE_UPDATED','DEACTIVATED','REACTIVATED','ESCALATED','ACKNOWLEDGED')),
 evidence jsonb NOT NULL, PRIMARY KEY(alert_id,alert_version),
 CHECK(NOT evidence ?| ARRAY['acknowledgedBy','issuer','subject','snapshot']),
 CHECK(evidence @> jsonb_build_object('alertId',alert_id,'alertVersion',alert_version,'attentionVersion',attention_version,'kind',kind))
);
CREATE TABLE privacy_command (
 issuer varchar(2048) NOT NULL, subject varchar(200) NOT NULL, idempotency_key uuid NOT NULL,
 alert_id uuid NOT NULL REFERENCES privacy_alert(id), path text NOT NULL, request_hash char(64) NOT NULL,
 attention_version bigint NOT NULL CHECK(attention_version>=1), response_body text NOT NULL,
 completed_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 PRIMARY KEY(issuer,subject,idempotency_key), CHECK(expires_at<=completed_at+interval '30 days'),
 CHECK(request_hash ~ '^[0-9a-f]{64}$'), CHECK(length(issuer)>0 AND length(subject)>0)
);
CREATE TABLE privacy_removal (
 issuer varchar(2048) NOT NULL, subject varchar(200) NOT NULL, request_id uuid NOT NULL UNIQUE,
 requested_at timestamptz NOT NULL, key_cutoff timestamptz NOT NULL,
 completed_at timestamptz, expires_at timestamptz,
 PRIMARY KEY(issuer,subject), CHECK(key_cutoff=requested_at+interval '5 minutes'),
 CHECK((completed_at IS NULL)=(expires_at IS NULL)),
 CHECK(expires_at IS NULL OR expires_at=completed_at+interval '66 days')
);
CREATE FUNCTION privacy_root_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE i crop_health_incident%ROWTYPE;
BEGIN
 SELECT * INTO i FROM crop_health_incident WHERE id=NEW.incident_id;
 IF NOT FOUND OR i.estate_id<>NEW.estate_id OR i.block_id<>NEW.block_id OR i.version<>NEW.source_version OR
   NEW.body->>'severity'<>i.severity OR NEW.body->>'incidentStatus'<>i.status OR NEW.body->>'category'<>i.category OR
   (NEW.state='ACTIVE') IS DISTINCT FROM (i.status IN('OPEN','IN_REVIEW') AND i.severity IN('HIGH','CRITICAL')) THEN
   RAISE EXCEPTION 'Privacy source mismatch' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
   IF NEW.id<>OLD.id OR NEW.incident_id<>OLD.incident_id OR NEW.estate_id<>OLD.estate_id OR NEW.block_id<>OLD.block_id OR
      NEW.alert_version<>OLD.alert_version::numeric+1 OR NEW.attention_version NOT BETWEEN OLD.attention_version AND OLD.attention_version::numeric+1 OR
      NEW.source_version NOT BETWEEN OLD.source_version AND OLD.source_version::numeric+1 OR
      (OLD.inactive_since IS NOT NULL AND NEW.inactive_since IS NOT NULL AND NEW.inactive_since<>OLD.inactive_since) OR
      NEW.read_expired_through<OLD.read_expired_through OR NEW.history_expired_through<OLD.history_expired_through OR
      (OLD.last_history_expired_at IS NOT NULL AND (NEW.last_history_expired_at IS NULL OR NEW.last_history_expired_at<OLD.last_history_expired_at)) THEN
     RAISE EXCEPTION 'Privacy immutable identity/version/watermark' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER privacy_root_guard BEFORE INSERT OR UPDATE ON privacy_alert FOR EACH ROW EXECUTE FUNCTION privacy_root_guard();
CREATE TRIGGER privacy_ack_immutable BEFORE UPDATE ON privacy_ack FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE TRIGGER privacy_evidence_immutable BEFORE UPDATE ON privacy_evidence FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE TRIGGER privacy_command_immutable BEFORE UPDATE ON privacy_command FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE INDEX privacy_scope_attention ON privacy_alert(estate_id,state,attention_at DESC,id DESC);
CREATE INDEX privacy_receipt_expiry ON privacy_command(expires_at,alert_id);
-- Only owner or an explicitly authorized isolated maintenance principal may execute these functions.
-- No HTTP endpoint, caller-provided cutoff or broad runtime DELETE privilege.
CREATE FUNCTION privacy_request_removal(p_issuer text,p_subject text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
DECLARE request uuid; at timestamptz:=clock_timestamp();
BEGIN
 IF length(p_issuer) NOT BETWEEN 1 AND 2048 OR length(p_subject) NOT BETWEEN 1 AND 200 OR p_issuer IS NULL OR p_subject IS NULL THEN
  RAISE EXCEPTION 'Verified identity required'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(p_issuer||chr(10)||p_subject,0));
 INSERT INTO privacy_removal(issuer,subject,request_id,requested_at,key_cutoff)
 VALUES(p_issuer,p_subject,gen_random_uuid(),at,at+interval '5 minutes')
 ON CONFLICT(issuer,subject) DO UPDATE SET request_id=gen_random_uuid(),requested_at=EXCLUDED.requested_at,key_cutoff=EXCLUDED.key_cutoff,completed_at=NULL,expires_at=NULL
 RETURNING request_id INTO request;
 RETURN request;
END $$;
REVOKE ALL ON FUNCTION privacy_request_removal(text,text) FROM PUBLIC;
CREATE FUNCTION privacy_cleanup(p_limit integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
DECLARE r privacy_alert%ROWTYPE; at timestamptz:=clock_timestamp(); n integer:=0;
BEGIN
 IF p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'Batch limit 1..200 required'; END IF;
 FOR r IN SELECT a.* FROM privacy_alert a WHERE
   EXISTS(SELECT 1 FROM privacy_read x WHERE x.alert_id=a.id AND a.inactive_since+interval '30 days'<=at) OR
   EXISTS(SELECT 1 FROM privacy_evidence e WHERE e.alert_id=a.id AND a.inactive_since+interval '365 days'<=at) OR
   EXISTS(SELECT 1 FROM privacy_ack x WHERE x.alert_id=a.id AND (a.inactive_since+interval '365 days'<=at OR (x.attention_version<=a.read_expired_through AND a.last_history_expired_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM privacy_evidence e WHERE e.alert_id=a.id AND e.attention_version=x.attention_version)))) OR
   EXISTS(SELECT 1 FROM privacy_read x WHERE x.alert_id=a.id AND (x.attention_version<=a.read_expired_through OR EXISTS(SELECT 1 FROM privacy_removal m WHERE m.issuer=x.issuer AND m.subject=x.subject AND x.read_at<=m.key_cutoff))) OR
   EXISTS(SELECT 1 FROM privacy_command c WHERE c.alert_id=a.id AND (c.expires_at<=at OR c.attention_version<=a.read_expired_through OR EXISTS(SELECT 1 FROM privacy_removal m WHERE m.issuer=c.issuer AND m.subject=c.subject AND c.completed_at<=m.key_cutoff))) OR
   EXISTS(SELECT 1 FROM privacy_evidence e WHERE e.alert_id=a.id AND e.alert_version<=a.history_expired_through)
   ORDER BY a.id LIMIT p_limit FOR UPDATE SKIP LOCKED
 LOOP
   DELETE FROM privacy_read x WHERE x.alert_id=r.id AND (x.attention_version<=r.read_expired_through OR
      (r.inactive_since IS NOT NULL AND r.inactive_since+interval '30 days'<=at) OR
      EXISTS(SELECT 1 FROM privacy_removal m WHERE m.issuer=x.issuer AND m.subject=x.subject AND x.read_at<=m.key_cutoff));
   DELETE FROM privacy_command c WHERE c.alert_id=r.id AND (c.expires_at<=at OR c.attention_version<=r.read_expired_through OR
      (r.inactive_since IS NOT NULL AND r.inactive_since+interval '30 days'<=at) OR
      EXISTS(SELECT 1 FROM privacy_removal m WHERE m.issuer=c.issuer AND m.subject=c.subject AND c.completed_at<=m.key_cutoff));
   DELETE FROM privacy_evidence e WHERE e.alert_id=r.id AND (e.alert_version<=r.history_expired_through OR
      (r.inactive_since IS NOT NULL AND r.inactive_since+interval '365 days'<=at));
   DELETE FROM privacy_ack x WHERE x.alert_id=r.id AND (x.attention_version<=r.read_expired_through AND r.last_history_expired_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM privacy_evidence e WHERE e.alert_id=r.id AND e.attention_version=x.attention_version) OR
      (r.inactive_since IS NOT NULL AND r.inactive_since+interval '365 days'<=at));
   n:=n+1;
 END LOOP;
 UPDATE privacy_removal m SET completed_at=at,expires_at=at+interval '66 days' WHERE m.completed_at IS NULL
  AND NOT EXISTS(SELECT 1 FROM privacy_read x WHERE x.issuer=m.issuer AND x.subject=m.subject AND x.read_at<=m.key_cutoff)
  AND NOT EXISTS(SELECT 1 FROM privacy_command c WHERE c.issuer=m.issuer AND c.subject=m.subject AND c.completed_at<=m.key_cutoff);
 DELETE FROM privacy_removal WHERE expires_at<=at;
 RETURN n;
END $$;
REVOKE ALL ON FUNCTION privacy_cleanup(integer) FROM PUBLIC;
DO $$ DECLARE s text:=current_schema(); BEGIN
 EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I.privacy_alert,%I.privacy_read TO agrosphere_operations_app',s,s);
 EXECUTE format('GRANT SELECT,INSERT ON %I.privacy_ack,%I.privacy_evidence,%I.privacy_command TO agrosphere_operations_app',s,s,s);
 EXECUTE format('GRANT SELECT ON %I.privacy_removal TO agrosphere_operations_app',s);
END $$;
