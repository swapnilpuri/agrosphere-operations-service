-- Additive Operations-owned evidence model. Provision the reviewed restricted role
-- before migration. This migration does not create logins or handle passwords.
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='agrosphere_operations_app') THEN
  RAISE EXCEPTION 'Provision Operations runtime role before V3; do not reuse migration-owner credentials';
 END IF;
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='agrosphere_operations_app'
            AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
  RAISE EXCEPTION 'Operations runtime role has prohibited elevated privileges';
 END IF;
 IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member
            WHERE r.rolname='agrosphere_operations_app')
    OR EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid=n.nspowner
               WHERE n.nspname=current_schema() AND r.rolname='agrosphere_operations_app')
    OR EXISTS (SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid=d.datdba
               WHERE d.datname=current_database() AND r.rolname='agrosphere_operations_app') THEN
  RAISE EXCEPTION 'Operations runtime role must not inherit roles or own migration objects';
 END IF;
END $$;

CREATE TABLE latex_collection (
 id uuid PRIMARY KEY,
 tapping_record_id uuid NOT NULL REFERENCES tapping_record(id) ON DELETE RESTRICT,
 assignment_id uuid NOT NULL REFERENCES tapping_assignment(id) ON DELETE RESTRICT,
 estate_id uuid NOT NULL, block_id uuid NOT NULL, division_id_snapshot uuid NOT NULL,
 worker_id varchar(200) NOT NULL CHECK(length(btrim(worker_id))>0), tapping_date date NOT NULL,
 estate_timezone varchar(100) NOT NULL CHECK(length(btrim(estate_timezone))>0),
 estate_version_snapshot bigint NOT NULL CHECK(estate_version_snapshot>=0),
 block_version_snapshot bigint NOT NULL CHECK(block_version_snapshot>=0),
 master_validated_at timestamptz NOT NULL,
 collection_reference varchar(64) NOT NULL CHECK(collection_reference ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
 replacement_of_collection_id uuid REFERENCES latex_collection(id) ON DELETE RESTRICT,
 collected_at timestamptz NOT NULL, collection_date date NOT NULL,
 wet_latex_kg numeric(9,3) NOT NULL CHECK(wet_latex_kg>0 AND wet_latex_kg<=999999.999),
 weight_method varchar(20) NOT NULL CHECK(weight_method IN ('SCALE','MANUAL_ENTRY')),
 weight_source_reference varchar(200) NOT NULL CHECK(length(btrim(weight_source_reference))>0),
 weight_instrument_reference varchar(200) CHECK(length(btrim(weight_instrument_reference))>0),
 backdate_reason varchar(2000) CHECK(length(btrim(backdate_reason))>0),
 data_provenance varchar(12) NOT NULL CHECK(data_provenance IN ('REAL','SIMULATED')),
 status varchar(20) NOT NULL CHECK(status IN ('PENDING_DRC','CALCULATED','VOIDED')),
 selected_measurement_id uuid, dry_rubber_exact_kg numeric(13,7), dry_rubber_kg numeric(9,3),
 calculation_policy varchar(30) CHECK(calculation_policy='DRC_KG_V1'),
 version bigint NOT NULL CHECK(version>=0),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 created_by varchar(200) NOT NULL CHECK(length(btrim(created_by))>0),
 updated_by varchar(200) NOT NULL CHECK(length(btrim(updated_by))>0),
 UNIQUE(tapping_record_id,collection_reference),
 CHECK(id IS DISTINCT FROM replacement_of_collection_id),
 CHECK(collection_date>=tapping_date AND collected_at<=updated_at AND created_at<=updated_at
       AND master_validated_at<=created_at),
 CHECK((status='PENDING_DRC' AND selected_measurement_id IS NULL AND dry_rubber_exact_kg IS NULL
        AND dry_rubber_kg IS NULL AND calculation_policy IS NULL)
    OR (status='CALCULATED' AND selected_measurement_id IS NOT NULL AND dry_rubber_exact_kg IS NOT NULL
        AND dry_rubber_kg IS NOT NULL AND calculation_policy IS NOT NULL)
    OR (status='VOIDED' AND version>0 AND
       ((selected_measurement_id IS NULL AND dry_rubber_exact_kg IS NULL AND dry_rubber_kg IS NULL AND calculation_policy IS NULL)
        OR (selected_measurement_id IS NOT NULL AND dry_rubber_exact_kg IS NOT NULL AND dry_rubber_kg IS NOT NULL AND calculation_policy IS NOT NULL)))),
 CHECK(dry_rubber_exact_kg>=0 AND dry_rubber_exact_kg<=wet_latex_kg),
 CHECK(dry_rubber_kg=round(dry_rubber_exact_kg,3))
);

CREATE TABLE drc_measurement (
 id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES latex_collection(id) ON DELETE RESTRICT,
 introduced_version bigint NOT NULL CHECK(introduced_version>=1),
 percent numeric(5,2) NOT NULL CHECK(percent>=0 AND percent<=100),
 measured_at timestamptz NOT NULL,
 method varchar(24) NOT NULL CHECK(method IN ('LAB_REPORTED','FIELD_TEST')),
 source_reference varchar(200) NOT NULL CHECK(length(btrim(source_reference))>0),
 sample_reference varchar(200) CHECK(length(btrim(sample_reference))>0),
 instrument_reference varchar(200) CHECK(length(btrim(instrument_reference))>0),
 data_provenance varchar(12) NOT NULL CHECK(data_provenance IN ('REAL','SIMULATED')),
 recorded_at timestamptz NOT NULL, recorded_by varchar(200) NOT NULL CHECK(length(btrim(recorded_by))>0),
 UNIQUE(collection_id,id), UNIQUE(collection_id,introduced_version),
 CHECK(measured_at<=recorded_at), CHECK(method<>'LAB_REPORTED' OR sample_reference IS NOT NULL)
);

CREATE TABLE collection_revision (
 collection_id uuid NOT NULL REFERENCES latex_collection(id) ON DELETE RESTRICT,
 version bigint NOT NULL CHECK(version>=0),
 command_kind varchar(20) NOT NULL CHECK(command_kind IN ('CREATE','DRC','CORRECT','VOID')),
 snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
 recorded_at timestamptz NOT NULL, recorded_by varchar(200) NOT NULL CHECK(length(btrim(recorded_by))>0),
 correlation_id uuid NOT NULL, calculation_policy varchar(30) CHECK(calculation_policy='DRC_KG_V1'),
 PRIMARY KEY(collection_id,version), CHECK((command_kind='CREATE')=(version=0))
);

ALTER TABLE latex_collection ADD CONSTRAINT collection_selected_measurement_fk
 FOREIGN KEY(id,selected_measurement_id) REFERENCES drc_measurement(collection_id,id)
 ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE latex_collection ADD CONSTRAINT collection_current_revision_fk
 FOREIGN KEY(id,version) REFERENCES collection_revision(collection_id,version)
 DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE drc_measurement ADD CONSTRAINT measurement_introduced_revision_fk
 FOREIGN KEY(collection_id,introduced_version) REFERENCES collection_revision(collection_id,version)
 DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE collection_adjustment (
 id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES latex_collection(id) ON DELETE RESTRICT,
 before_version bigint NOT NULL CHECK(before_version>=0), after_version bigint NOT NULL CHECK(after_version>=1),
 kind varchar(12) NOT NULL CHECK(kind IN ('CORRECT','VOID')),
 reason varchar(2000) NOT NULL CHECK(length(btrim(reason))>0),
 approved_at timestamptz NOT NULL, approved_by varchar(200) NOT NULL CHECK(length(btrim(approved_by))>0),
 correlation_id uuid NOT NULL, UNIQUE(collection_id,after_version),
 CHECK(after_version-before_version=1),
 FOREIGN KEY(collection_id,before_version) REFERENCES collection_revision(collection_id,version)
 ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(collection_id,after_version) REFERENCES collection_revision(collection_id,version)
 ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE collection_command (
 actor varchar(200) NOT NULL CHECK(length(btrim(actor))>0), idempotency_key uuid NOT NULL,
 request_hash char(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 normalization_version integer NOT NULL CHECK(normalization_version=1),
 method varchar(8) NOT NULL CHECK(method='POST'),
 resource_path varchar(200) NOT NULL CHECK(resource_path LIKE '/api/v1/tapping/collections%'),
 collection_id uuid REFERENCES latex_collection(id) ON DELETE RESTRICT,
 response_status integer CHECK(response_status IN (200,201)),
 response_location varchar(250), response_body jsonb CHECK(jsonb_typeof(response_body)='object'),
 created_at timestamptz NOT NULL, completed_at timestamptz CHECK(completed_at>=created_at),
 PRIMARY KEY(actor,idempotency_key)
);

CREATE FUNCTION collection_root_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE original_record tapping_record%ROWTYPE; original_assignment tapping_assignment%ROWTYPE;
        replaced latex_collection%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Collection evidence cannot be deleted' USING ERRCODE='55000'; END IF;
 IF TG_OP='INSERT' THEN
  SELECT * INTO original_record FROM tapping_record WHERE id=NEW.tapping_record_id FOR SHARE;
  SELECT * INTO original_assignment FROM tapping_assignment WHERE id=NEW.assignment_id FOR SHARE;
  IF original_record.id IS NULL OR original_assignment.id IS NULL
     OR original_record.assignment_id<>NEW.assignment_id OR original_assignment.status<>'COMPLETED'
     OR original_record.completed_at IS NULL OR original_record.actual_tree_count<=0
     OR ROW(original_record.estate_id,original_record.block_id,original_record.worker_id,original_record.tapping_date)
        IS DISTINCT FROM ROW(NEW.estate_id,NEW.block_id,NEW.worker_id,NEW.tapping_date)
     OR ROW(original_assignment.estate_id,original_assignment.block_id,original_assignment.worker_id,original_assignment.tapping_date)
        IS DISTINCT FROM ROW(NEW.estate_id,NEW.block_id,NEW.worker_id,NEW.tapping_date)
     OR NEW.collected_at<original_record.completed_at OR NEW.version<>0 OR NEW.status<>'PENDING_DRC' THEN
   RAISE EXCEPTION 'Eligible immutable completed execution required' USING ERRCODE='23514';
  END IF;
  IF NEW.replacement_of_collection_id IS NOT NULL THEN
   SELECT * INTO replaced FROM latex_collection WHERE id=NEW.replacement_of_collection_id;
   IF replaced.id IS NULL OR replaced.status<>'VOIDED' OR replaced.tapping_record_id<>NEW.tapping_record_id
      OR replaced.data_provenance<>NEW.data_provenance OR replaced.collection_reference=NEW.collection_reference THEN
    RAISE EXCEPTION 'Invalid replacement collection' USING ERRCODE='23514';
   END IF;
  END IF;
 ELSE
  IF OLD.status='VOIDED' OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1
     OR NEW.updated_at<OLD.updated_at
     OR ROW(NEW.id,NEW.tapping_record_id,NEW.assignment_id,NEW.estate_id,NEW.block_id,NEW.worker_id,
            NEW.tapping_date,NEW.division_id_snapshot,NEW.estate_timezone,NEW.estate_version_snapshot,
            NEW.block_version_snapshot,NEW.master_validated_at,NEW.collection_reference,
            NEW.replacement_of_collection_id,NEW.data_provenance,NEW.created_at,NEW.created_by)
        IS DISTINCT FROM ROW(OLD.id,OLD.tapping_record_id,OLD.assignment_id,OLD.estate_id,OLD.block_id,OLD.worker_id,
            OLD.tapping_date,OLD.division_id_snapshot,OLD.estate_timezone,OLD.estate_version_snapshot,
            OLD.block_version_snapshot,OLD.master_validated_at,OLD.collection_reference,
            OLD.replacement_of_collection_id,OLD.data_provenance,OLD.created_at,OLD.created_by) THEN
   RAISE EXCEPTION 'Invalid immutable collection revision' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.collection_date<>(NEW.collected_at AT TIME ZONE NEW.estate_timezone)::date THEN
  RAISE EXCEPTION 'Collection date must match stored timezone' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER collection_root_guard BEFORE INSERT OR UPDATE OR DELETE ON latex_collection
 FOR EACH ROW EXECUTE FUNCTION collection_root_guard();

CREATE FUNCTION collection_append_only_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Immutable collection evidence' USING ERRCODE='55000'; END $$;
CREATE TRIGGER measurement_append_only BEFORE UPDATE OR DELETE ON drc_measurement
 FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE TRIGGER revision_append_only BEFORE UPDATE OR DELETE ON collection_revision
 FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();
CREATE TRIGGER adjustment_append_only BEFORE UPDATE OR DELETE ON collection_adjustment
 FOR EACH ROW EXECUTE FUNCTION collection_append_only_guard();

CREATE FUNCTION collection_command_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.completed_at IS NOT NULL THEN
  RAISE EXCEPTION 'Command receipt is immutable' USING ERRCODE='55000';
 END IF;
 IF ROW(NEW.actor,NEW.idempotency_key,NEW.request_hash,NEW.normalization_version,NEW.method,NEW.resource_path,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.actor,OLD.idempotency_key,OLD.request_hash,OLD.normalization_version,OLD.method,OLD.resource_path,OLD.created_at) THEN
  RAISE EXCEPTION 'Command identity is immutable' USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER command_guard BEFORE UPDATE OR DELETE ON collection_command
 FOR EACH ROW EXECUTE FUNCTION collection_command_guard();

CREATE FUNCTION collection_command_complete() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE result collection_command%ROWTYPE;
BEGIN
 SELECT * INTO result FROM collection_command WHERE actor=NEW.actor AND idempotency_key=NEW.idempotency_key;
 IF result.collection_id IS NULL OR result.response_status IS NULL OR result.response_body IS NULL
    OR result.completed_at IS NULL OR (result.response_status=201 AND result.response_location IS NULL) THEN
  RAISE EXCEPTION 'Incomplete collection command cannot commit' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER command_completion AFTER INSERT OR UPDATE ON collection_command
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION collection_command_complete();

CREATE INDEX collection_scope_date ON latex_collection(estate_id,collection_date,id);
CREATE INDEX collection_block_date ON latex_collection(estate_id,block_id,collection_date,id);
CREATE INDEX collection_division_date ON latex_collection(estate_id,division_id_snapshot,collection_date,id);
CREATE INDEX collection_worker_date ON latex_collection(estate_id,worker_id,collection_date,id);
CREATE INDEX collection_record ON latex_collection(tapping_record_id,id);
CREATE INDEX collection_pending_date ON latex_collection(estate_id,collection_date,id) WHERE status='PENDING_DRC';

DO $$
DECLARE service_schema text:=current_schema();
BEGIN
 EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM PUBLIC',service_schema);
 EXECUTE format('GRANT USAGE ON SCHEMA %I TO agrosphere_operations_app',service_schema);
 EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I.latex_collection,%I.collection_command,%I.tapping_schedule,%I.tapping_assignment,%I.tapping_record,%I.tapping_missed_reason,%I.outbox_event TO agrosphere_operations_app',
 service_schema,service_schema,service_schema,service_schema,service_schema,service_schema,service_schema);
 EXECUTE format('GRANT SELECT,INSERT ON %I.drc_measurement,%I.collection_revision,%I.collection_adjustment,%I.processed_event TO agrosphere_operations_app',
 service_schema,service_schema,service_schema,service_schema);
END $$;
