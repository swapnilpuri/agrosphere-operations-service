-- AN-1 additive, no backfill or existing-role/account/credential provisioning.
CREATE TABLE crop_health_alert (
 id uuid PRIMARY KEY, incident_id uuid NOT NULL REFERENCES crop_health_incident(id),
 rule_version varchar(32) NOT NULL CHECK(rule_version='CROP_HEALTH_HIGH_V1'),
 estate_id uuid NOT NULL, block_id uuid NOT NULL, source_version bigint NOT NULL CHECK(source_version>=0),
 alert_version bigint NOT NULL CHECK(alert_version>=1), attention_version bigint NOT NULL CHECK(attention_version BETWEEN 1 AND alert_version),
 state varchar(8) NOT NULL CHECK(state IN('ACTIVE','INACTIVE')), inactive_reason varchar(24),
 raised_at timestamptz NOT NULL, attention_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(incident_id,rule_version), UNIQUE(id,alert_version),
 CHECK(raised_at<=attention_at AND attention_at<=updated_at),
 CHECK((state='ACTIVE' AND inactive_reason IS NULL) OR (state='INACTIVE' AND inactive_reason IN('RESOLVED','VOIDED','BELOW_THRESHOLD'))),
 CHECK(snapshot @> jsonb_build_object('id',id,'incidentId',incident_id,'estateId',estate_id,'blockId',block_id,
   'sourceVersion',source_version,'alertVersion',alert_version,'attentionVersion',attention_version,'ruleVersion',rule_version,'dataProvenance','SIMULATED')),
 CHECK(((snapshot->>'raisedAt')::timestamptz=raised_at AND (snapshot->>'attentionAt')::timestamptz=attention_at AND (snapshot->>'updatedAt')::timestamptz=updated_at) IS TRUE),
 CHECK(snapshot ?& ARRAY['acknowledged','acknowledgedAt','acknowledgedBy']),
 CHECK(((snapshot->>'acknowledged')::boolean=(snapshot->'acknowledgedBy'<>'null'::jsonb) AND (snapshot->'acknowledgedAt'='null'::jsonb)=(snapshot->'acknowledgedBy'='null'::jsonb)) IS TRUE)
);
CREATE TABLE alert_transition (
 alert_id uuid NOT NULL REFERENCES crop_health_alert(id), alert_version bigint NOT NULL CHECK(alert_version>=1),
 source_version bigint NOT NULL CHECK(source_version>=0), attention_version bigint NOT NULL CHECK(attention_version BETWEEN 1 AND alert_version),
 kind varchar(24) NOT NULL CHECK(kind IN('RAISED','SOURCE_UPDATED','DEACTIVATED','REACTIVATED','ESCALATED','ACKNOWLEDGED')),
 recorded_at timestamptz NOT NULL, evidence jsonb NOT NULL, snapshot jsonb NOT NULL,
 PRIMARY KEY(alert_id,alert_version),
 CHECK(evidence @> jsonb_build_object('alertId',alert_id,'alertVersion',alert_version,'sourceVersion',source_version,'attentionVersion',attention_version,'kind',kind)),
 CHECK(((evidence->>'recordedAt')::timestamptz=recorded_at) IS TRUE),
 CHECK((kind='ACKNOWLEDGED')=(evidence->'acknowledgedBy'<>'null'::jsonb))
);
ALTER TABLE crop_health_alert ADD FOREIGN KEY(id,alert_version) REFERENCES alert_transition(alert_id,alert_version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE alert_read (
 alert_id uuid NOT NULL REFERENCES crop_health_alert(id), issuer varchar(2048) NOT NULL, subject varchar(200) NOT NULL,
 attention_version bigint NOT NULL CHECK(attention_version>=1), read_at timestamptz NOT NULL,
 PRIMARY KEY(alert_id,issuer,subject), CHECK(length(subject)>0 AND length(issuer)>0)
);
CREATE TABLE alert_acknowledgement (
 alert_id uuid NOT NULL REFERENCES crop_health_alert(id), attention_version bigint NOT NULL CHECK(attention_version>=1),
 issuer varchar(2048) NOT NULL, subject varchar(200) NOT NULL, acknowledged_at timestamptz NOT NULL,
 PRIMARY KEY(alert_id,attention_version), CHECK(length(subject)>0 AND length(issuer)>0)
);
CREATE TABLE alert_command (
 issuer varchar(2048) NOT NULL, subject varchar(200) NOT NULL, idempotency_key uuid NOT NULL,
 estate_id uuid NOT NULL, alert_id uuid NOT NULL REFERENCES crop_health_alert(id),
 permission varchar(8) NOT NULL CHECK(permission IN('READ','MANAGER')),
 resource_path text NOT NULL, request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 response_body text, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
 PRIMARY KEY(issuer,subject,idempotency_key), CHECK((response_body IS NULL)=(completed_at IS NULL))
);
CREATE FUNCTION alert_evidence_consistency() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE a crop_health_alert%ROWTYPE; i crop_health_incident%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='crop_health_alert' THEN
  SELECT * INTO i FROM crop_health_incident WHERE id=NEW.incident_id;
  IF i.estate_id<>NEW.estate_id OR i.block_id<>NEW.block_id OR i.version<>NEW.source_version OR
    NEW.snapshot->>'severity'<>i.severity OR NEW.snapshot->>'category'<>i.category OR NEW.snapshot->>'incidentStatus'<>i.status OR
    (NEW.state='ACTIVE') IS DISTINCT FROM (i.status IN('OPEN','IN_REVIEW') AND i.severity IN('HIGH','CRITICAL')) THEN
    RAISE EXCEPTION 'Alert/source mismatch' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM alert_transition t WHERE t.alert_id=NEW.id AND t.alert_version=NEW.alert_version AND t.snapshot=NEW.snapshot) THEN
    RAISE EXCEPTION 'Alert transition missing/mismatched' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='alert_read' THEN
  SELECT * INTO a FROM crop_health_alert WHERE id=NEW.alert_id;
  IF NEW.attention_version>a.attention_version OR NEW.read_at<a.raised_at OR
    (NEW.attention_version=a.attention_version AND NEW.read_at<a.attention_at) THEN RAISE EXCEPTION 'Invalid read marker' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='alert_acknowledgement' THEN
  IF NOT EXISTS(SELECT 1 FROM alert_transition t WHERE t.alert_id=NEW.alert_id AND t.attention_version=NEW.attention_version AND t.kind='ACKNOWLEDGED'
    AND t.evidence#>>'{acknowledgedBy,issuer}'=NEW.issuer AND t.evidence#>>'{acknowledgedBy,subject}'=NEW.subject AND t.recorded_at=NEW.acknowledged_at) THEN
    RAISE EXCEPTION 'Acknowledgement evidence missing' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='alert_command' THEN
  IF NOT EXISTS(SELECT 1 FROM alert_command c WHERE c.issuer=NEW.issuer AND c.subject=NEW.subject AND c.idempotency_key=NEW.idempotency_key AND c.completed_at IS NOT NULL) THEN
   RAISE EXCEPTION 'Incomplete alert receipt' USING ERRCODE='23514'; END IF;
 END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER alert_root_evidence AFTER INSERT OR UPDATE ON crop_health_alert DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION alert_evidence_consistency();
CREATE CONSTRAINT TRIGGER alert_read_evidence AFTER INSERT OR UPDATE ON alert_read DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION alert_evidence_consistency();
CREATE CONSTRAINT TRIGGER alert_ack_evidence AFTER INSERT ON alert_acknowledgement DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION alert_evidence_consistency();
CREATE CONSTRAINT TRIGGER alert_receipt_complete AFTER INSERT OR UPDATE ON alert_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION alert_evidence_consistency();
CREATE TRIGGER alert_transition_immutable BEFORE UPDATE OR DELETE ON alert_transition FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE TRIGGER alert_ack_immutable BEFORE UPDATE OR DELETE ON alert_acknowledgement FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE FUNCTION alert_update_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='alert_command' THEN
  IF OLD.completed_at IS NOT NULL THEN RAISE EXCEPTION 'Completed receipt immutable' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='alert_read' THEN
  IF NEW.alert_id<>OLD.alert_id OR NEW.issuer<>OLD.issuer OR NEW.subject<>OLD.subject OR NEW.attention_version<OLD.attention_version OR NEW.read_at<OLD.read_at THEN
   RAISE EXCEPTION 'Read identity/version immutable' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='crop_health_alert' THEN
  IF NEW.id<>OLD.id OR NEW.incident_id<>OLD.incident_id OR NEW.estate_id<>OLD.estate_id OR NEW.block_id<>OLD.block_id OR NEW.rule_version<>OLD.rule_version OR
   NEW.alert_version<>OLD.alert_version::numeric+1 OR NEW.attention_version NOT BETWEEN OLD.attention_version AND OLD.attention_version::numeric+1 OR NEW.source_version NOT BETWEEN OLD.source_version AND OLD.source_version::numeric+1 OR NEW.raised_at<>OLD.raised_at OR NEW.updated_at<OLD.updated_at THEN
   RAISE EXCEPTION 'Alert identity/version immutable' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER alert_root_update BEFORE UPDATE ON crop_health_alert FOR EACH ROW EXECUTE FUNCTION alert_update_guard();
CREATE TRIGGER alert_read_update BEFORE UPDATE ON alert_read FOR EACH ROW EXECUTE FUNCTION alert_update_guard();
CREATE TRIGGER alert_receipt_update BEFORE UPDATE ON alert_command FOR EACH ROW EXECUTE FUNCTION alert_update_guard();
CREATE INDEX alert_scope_attention ON crop_health_alert(estate_id,state,attention_at DESC,id DESC);
CREATE INDEX alert_block_attention ON crop_health_alert(estate_id,block_id,state,attention_at DESC,id DESC);
DO $$ DECLARE s text:=current_schema(); BEGIN
 EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I.crop_health_alert,%I.alert_read,%I.alert_command TO agrosphere_operations_app',s,s,s);
 EXECUTE format('GRANT SELECT,INSERT ON %I.alert_transition,%I.alert_acknowledgement TO agrosphere_operations_app',s,s);
END $$;
