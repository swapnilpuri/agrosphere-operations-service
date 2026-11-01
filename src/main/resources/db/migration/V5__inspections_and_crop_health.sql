-- Additive II-1. No role/credential creation, legacy rewrites or destructive rollback.
CREATE TABLE field_inspection (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, block_id uuid NOT NULL, estate_timezone varchar(100) NOT NULL,
 data_provenance varchar(16) NOT NULL CHECK(data_provenance='SIMULATED'), observed_at timestamptz NOT NULL,
 version bigint NOT NULL CHECK(version>=0), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 created_by varchar(200) NOT NULL, updated_by varchar(200) NOT NULL, snapshot jsonb NOT NULL,
 CHECK(observed_at<=updated_at AND created_at<=updated_at),
 CHECK(snapshot @> jsonb_build_object('id',id,'estateId',estate_id,'blockId',block_id,'estateTimezone',estate_timezone,
  'dataProvenance',data_provenance,'version',version,'createdBy',created_by,'updatedBy',updated_by)),
 CHECK(((snapshot->>'observedAt')::timestamptz=observed_at) IS TRUE AND ((snapshot->>'createdAt')::timestamptz=created_at) IS TRUE
  AND ((snapshot->>'updatedAt')::timestamptz=updated_at) IS TRUE),
 outcome varchar(16) NOT NULL CHECK(outcome IN('CLEAR','FOLLOW_UP')),
 state varchar(16) NOT NULL CHECK(state IN('RECORDED','VOIDED')), linked_incident_id uuid UNIQUE,
 CHECK(outcome<>'FOLLOW_UP' OR linked_incident_id IS NOT NULL),
 CHECK(snapshot @> jsonb_build_object('outcome',outcome,'state',state,'linkedIncidentId',linked_incident_id)),
 CHECK(length(btrim(snapshot->>'findings')) BETWEEN 1 AND 2000)
);
CREATE TABLE crop_health_incident (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, block_id uuid NOT NULL, estate_timezone varchar(100) NOT NULL,
 data_provenance varchar(16) NOT NULL CHECK(data_provenance='SIMULATED'), observed_at timestamptz NOT NULL,
 version bigint NOT NULL CHECK(version>=0), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 created_by varchar(200) NOT NULL, updated_by varchar(200) NOT NULL, snapshot jsonb NOT NULL,
 CHECK(observed_at<=updated_at AND created_at<=updated_at),
 CHECK(snapshot @> jsonb_build_object('id',id,'estateId',estate_id,'blockId',block_id,'estateTimezone',estate_timezone,
  'dataProvenance',data_provenance,'version',version,'createdBy',created_by,'updatedBy',updated_by)),
 CHECK(((snapshot->>'observedAt')::timestamptz=observed_at) IS TRUE AND ((snapshot->>'createdAt')::timestamptz=created_at) IS TRUE
  AND ((snapshot->>'updatedAt')::timestamptz=updated_at) IS TRUE),
 inspection_id uuid UNIQUE, category varchar(32) NOT NULL CHECK(category IN('PEST_SIGNS','DISEASE_SIGNS','TREE_DAMAGE','OTHER_CROP_HEALTH')),
 severity varchar(16) NOT NULL CHECK(severity IN('LOW','MEDIUM','HIGH','CRITICAL')),
 status varchar(16) NOT NULL CHECK(status IN('OPEN','IN_REVIEW','RESOLVED','VOIDED')),
 resolved_at timestamptz, earliest_resolution_at timestamptz,
 CHECK(id IS DISTINCT FROM inspection_id),
 CHECK((status<>'RESOLVED' OR resolved_at IS NOT NULL) AND (status NOT IN('OPEN','IN_REVIEW') OR resolved_at IS NULL)),
 CHECK(resolved_at IS NULL OR (observed_at<=resolved_at AND resolved_at<=updated_at AND earliest_resolution_at IS NOT NULL)),
 CHECK(earliest_resolution_at IS NULL OR (observed_at<=earliest_resolution_at AND earliest_resolution_at<=updated_at)),
 CHECK(resolved_at IS NULL OR earliest_resolution_at<=resolved_at),
 CHECK(snapshot @> jsonb_build_object('inspectionId',inspection_id,'category',category,'severity',severity,'status',status)),
 CHECK(((snapshot->>'resolvedAt')::timestamptz IS NOT DISTINCT FROM resolved_at) AND
  ((snapshot->>'earliestRetainedResolutionAt')::timestamptz IS NOT DISTINCT FROM earliest_resolution_at)),
 CHECK(length(btrim(snapshot->>'summary')) BETWEEN 1 AND 200 AND length(btrim(snapshot->>'details')) BETWEEN 1 AND 2000),
 CHECK((resolved_at IS NULL AND snapshot->'resolutionNote'='null'::jsonb) OR
  (resolved_at IS NOT NULL AND length(btrim(snapshot->>'resolutionNote')) BETWEEN 1 AND 1000))
);
ALTER TABLE field_inspection ADD FOREIGN KEY(linked_incident_id) REFERENCES crop_health_incident(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE crop_health_incident ADD FOREIGN KEY(inspection_id) REFERENCES field_inspection(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE inspection_revision (
 root_id uuid NOT NULL REFERENCES field_inspection(id), version bigint NOT NULL CHECK(version>=0),
 command_kind varchar(16) NOT NULL CHECK(command_kind IN('RECORD','CORRECT','VOID')), reason varchar(1000),
 recorded_at timestamptz NOT NULL, recorded_by varchar(200) NOT NULL, correlation_id uuid NOT NULL,
 snapshot jsonb NOT NULL, event_id uuid NOT NULL UNIQUE REFERENCES outbox_event(event_id) DEFERRABLE INITIALLY DEFERRED,
 PRIMARY KEY(root_id,version),
 CHECK((version=0 AND command_kind IN('RECORD','REPORT')) OR (version>0 AND command_kind NOT IN('RECORD','REPORT') AND length(btrim(reason))>0))
);
ALTER TABLE field_inspection ADD CONSTRAINT inspection_revision_current FOREIGN KEY(id,version) REFERENCES inspection_revision(root_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER inspection_revision_immutable BEFORE UPDATE OR DELETE ON inspection_revision FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE TABLE incident_revision (
 root_id uuid NOT NULL REFERENCES crop_health_incident(id), version bigint NOT NULL CHECK(version>=0),
 command_kind varchar(16) NOT NULL CHECK(command_kind IN('REPORT','AMEND','TRIAGE','RESOLVE','REOPEN','VOID','CORRECT')), reason varchar(1000),
 recorded_at timestamptz NOT NULL, recorded_by varchar(200) NOT NULL, correlation_id uuid NOT NULL,
 snapshot jsonb NOT NULL, event_id uuid NOT NULL UNIQUE REFERENCES outbox_event(event_id) DEFERRABLE INITIALLY DEFERRED,
 PRIMARY KEY(root_id,version),
 CHECK((version=0 AND command_kind IN('RECORD','REPORT')) OR (version>0 AND command_kind NOT IN('RECORD','REPORT') AND length(btrim(reason))>0))
);
ALTER TABLE crop_health_incident ADD CONSTRAINT incident_revision_current FOREIGN KEY(id,version) REFERENCES incident_revision(root_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER incident_revision_immutable BEFORE UPDATE OR DELETE ON incident_revision FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE TABLE crop_health_command (
 actor varchar(200) NOT NULL CHECK(length(btrim(actor))>0), idempotency_key uuid NOT NULL, method varchar(8) NOT NULL CHECK(method='POST'),
 resource_path varchar(200) NOT NULL, request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 permission_class varchar(16) NOT NULL DEFAULT 'MANAGER' CHECK(permission_class IN('MANAGER','SUPERVISOR')),
 normalization_version integer NOT NULL DEFAULT 1 CHECK(normalization_version=1), created_at timestamptz NOT NULL,
 completed_at timestamptz, response_status integer CHECK(response_status IN(200,201)), response_body text, response_location text,
 PRIMARY KEY(actor,idempotency_key)
);
CREATE TRIGGER crop_command_guard BEFORE UPDATE OR DELETE ON crop_health_command FOR EACH ROW EXECUTE FUNCTION collection_command_guard();
CREATE FUNCTION crop_receipt_complete() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE r crop_health_command%ROWTYPE;
BEGIN
 SELECT * INTO r FROM crop_health_command WHERE actor=NEW.actor AND idempotency_key=NEW.idempotency_key;
 IF r.completed_at IS NULL OR r.response_status IS NULL OR r.response_body IS NULL OR
  (r.response_status=201 AND (r.response_location IS NULL OR length(r.response_location)=0)) THEN
  RAISE EXCEPTION 'Incomplete crop command' USING ERRCODE='23514';
 END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER crop_receipt_complete AFTER INSERT OR UPDATE ON crop_health_command DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION crop_receipt_complete();

CREATE FUNCTION crop_root_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Crop evidence deletion prohibited' USING ERRCODE='55000'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 OR NEW.created_at<>NEW.updated_at OR NEW.created_by<>NEW.updated_by OR
   (TG_TABLE_NAME='field_inspection' AND NEW.snapshot->>'state'<>'RECORDED') OR
   (TG_TABLE_NAME='crop_health_incident' AND NEW.snapshot->>'status'<>'OPEN') THEN
   RAISE EXCEPTION 'Invalid initial crop revision' USING ERRCODE='23514';
  END IF;
 ELSE
  IF OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at OR
   ROW(NEW.id,NEW.estate_id,NEW.block_id,NEW.estate_timezone,NEW.data_provenance,NEW.created_at,NEW.created_by)
   IS DISTINCT FROM ROW(OLD.id,OLD.estate_id,OLD.block_id,OLD.estate_timezone,OLD.data_provenance,OLD.created_at,OLD.created_by) THEN
   RAISE EXCEPTION 'Invalid crop identity/version' USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='field_inspection' THEN
   IF OLD.snapshot->>'state'='VOIDED' OR (OLD.snapshot->>'linkedIncidentId' IS NOT NULL AND NEW.snapshot->>'linkedIncidentId' IS DISTINCT FROM OLD.snapshot->>'linkedIncidentId') THEN
    RAISE EXCEPTION 'Immutable inspection/link' USING ERRCODE='23514';
   END IF;
  ELSE
   IF OLD.snapshot->>'status'='VOIDED' OR NEW.snapshot->>'inspectionId' IS DISTINCT FROM OLD.snapshot->>'inspectionId' OR
    (OLD.snapshot->>'earliestRetainedResolutionAt' IS NOT NULL AND
     (NEW.snapshot->>'earliestRetainedResolutionAt' IS NULL OR
      (NEW.snapshot->>'earliestRetainedResolutionAt')::timestamptz>(OLD.snapshot->>'earliestRetainedResolutionAt')::timestamptz)) THEN
    RAISE EXCEPTION 'Immutable incident/link/retained chronology' USING ERRCODE='23514';
   END IF;
  END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER inspection_guard BEFORE INSERT OR UPDATE OR DELETE ON field_inspection FOR EACH ROW EXECUTE FUNCTION crop_root_guard();
CREATE TRIGGER incident_guard BEFORE INSERT OR UPDATE OR DELETE ON crop_health_incident FOR EACH ROW EXECUTE FUNCTION crop_root_guard();

CREATE FUNCTION crop_link_consistency() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE i field_inspection%ROWTYPE; c crop_health_incident%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='field_inspection' THEN
  SELECT * INTO i FROM field_inspection WHERE id=NEW.id;
  IF i.linked_incident_id IS NULL THEN RETURN NULL; END IF;
  SELECT * INTO c FROM crop_health_incident WHERE id=i.linked_incident_id;
 ELSE
  SELECT * INTO c FROM crop_health_incident WHERE id=NEW.id;
  IF c.inspection_id IS NULL THEN RETURN NULL; END IF;
  SELECT * INTO i FROM field_inspection WHERE id=c.inspection_id;
 END IF;
 IF i.id IS NULL OR c.id IS NULL OR i.linked_incident_id IS DISTINCT FROM c.id OR c.inspection_id IS DISTINCT FROM i.id OR
  ROW(i.estate_id,i.block_id,i.estate_timezone,i.data_provenance) IS DISTINCT FROM ROW(c.estate_id,c.block_id,c.estate_timezone,c.data_provenance) OR
  ((i.outcome='CLEAR' OR i.state='VOIDED') AND c.status<>'VOIDED') THEN
  RAISE EXCEPTION 'Inconsistent inspection/incident link' USING ERRCODE='23514';
 END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER inspection_link AFTER INSERT OR UPDATE ON field_inspection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crop_link_consistency();
CREATE CONSTRAINT TRIGGER incident_link AFTER INSERT OR UPDATE ON crop_health_incident DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crop_link_consistency();

CREATE FUNCTION crop_revision_consistency() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE root_snapshot jsonb; e outbox_event%ROWTYPE; minimal jsonb; expected_type text;
BEGIN
 IF TG_TABLE_NAME='inspection_revision' THEN
  SELECT snapshot INTO root_snapshot FROM field_inspection WHERE id=NEW.root_id AND version=NEW.version;
  minimal:=NEW.snapshot-ARRAY['findings','createdAt','updatedAt','createdBy','updatedBy'];
  expected_type:=CASE WHEN NEW.version=0 THEN 'FieldInspectionRecorded' ELSE 'FieldInspectionChanged' END;
 ELSE
  SELECT snapshot INTO root_snapshot FROM crop_health_incident WHERE id=NEW.root_id AND version=NEW.version;
  minimal:=NEW.snapshot-ARRAY['summary','details','resolutionNote','earliestRetainedResolutionAt','createdAt','updatedAt','createdBy','updatedBy'];
  expected_type:=CASE WHEN NEW.version=0 THEN 'CropHealthIncidentReported' ELSE 'CropHealthIncidentChanged' END;
 END IF;
 -- Later roots may have advanced in the same transaction; this revision remains immutable evidence.
 IF root_snapshot IS NOT NULL AND root_snapshot<>NEW.snapshot THEN RAISE EXCEPTION 'Revision/root mismatch' USING ERRCODE='23514'; END IF;
 SELECT * INTO e FROM outbox_event WHERE event_id=NEW.event_id;
 IF e.event_id IS NULL OR e.aggregate_id<>NEW.root_id OR e.event_type<>expected_type OR e.event_version<>1 OR
  e.correlation_id<>NEW.correlation_id OR e.occurred_at<>NEW.recorded_at OR
  NOT (e.payload @> jsonb_build_object('eventId',NEW.event_id,'eventType',expected_type,'eventVersion',1,
    'source','agrosphere-operations-service','correlationId',NEW.correlation_id,'estateId',NEW.snapshot->'estateId',
    'blockId',NEW.snapshot->'blockId','dataProvenance','SIMULATED','aggregateId',NEW.root_id,'aggregateVersion',NEW.version)) OR
  e.payload#>'{payload,snapshot}' IS DISTINCT FROM minimal OR
  (e.payload->>'timestamp')::timestamptz IS DISTINCT FROM NEW.recorded_at OR
  (NEW.snapshot->>'updatedAt')::timestamptz IS DISTINCT FROM NEW.recorded_at OR NEW.snapshot->>'updatedBy' IS DISTINCT FROM NEW.recorded_by OR
  (NEW.version>0 AND ((e.payload#>>'{payload,previousVersion}')::bigint IS DISTINCT FROM NEW.version-1 OR
    e.payload#>>'{payload,changeKind}' IS DISTINCT FROM NEW.command_kind)) THEN
  RAISE EXCEPTION 'Revision/outbox mismatch' USING ERRCODE='23514';
 END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER inspection_revision_match AFTER INSERT ON inspection_revision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crop_revision_consistency();
CREATE CONSTRAINT TRIGGER incident_revision_match AFTER INSERT ON incident_revision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crop_revision_consistency();
CREATE INDEX inspection_scope_observed ON field_inspection(estate_id,data_provenance,observed_at DESC,id DESC);
CREATE INDEX inspection_block_observed ON field_inspection(estate_id,block_id,observed_at DESC,id DESC);
CREATE INDEX incident_scope_observed ON crop_health_incident(estate_id,data_provenance,status,observed_at DESC,id DESC);
CREATE INDEX incident_block_observed ON crop_health_incident(estate_id,block_id,observed_at DESC,id DESC);
DO $$ DECLARE s text:=current_schema(); BEGIN
 EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I.field_inspection,%I.crop_health_incident,%I.crop_health_command TO agrosphere_operations_app',s,s,s);
 EXECUTE format('GRANT SELECT,INSERT ON %I.inspection_revision,%I.incident_revision TO agrosphere_operations_app',s,s);
END $$;
