-- Additive WA-1 persistence. No role creation, password handling or old-table rewrites.
-- REAL use deliberately remains blocked pending the separately accepted D09 policy.
CREATE TABLE worker_registration (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, worker_code varchar(64) NOT NULL,
 display_name varchar(200) NOT NULL CHECK (length(btrim(display_name))>0), division_id uuid,
 data_provenance varchar(16) NOT NULL CHECK(data_provenance='SIMULATED'),
 version bigint NOT NULL CHECK(version>=0), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 created_by varchar(200) NOT NULL, updated_by varchar(200) NOT NULL, snapshot jsonb NOT NULL,
 UNIQUE(estate_id,worker_code), UNIQUE(id,estate_id),
 CHECK(worker_code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'), CHECK(updated_at>=created_at),
 CHECK(snapshot @> jsonb_build_object('id',id,'estateId',estate_id,'workerCode',worker_code,'displayName',display_name,
   'divisionId',division_id,'dataProvenance',data_provenance,'audit',jsonb_build_object('version',version,'createdBy',created_by,'updatedBy',updated_by))),
 CHECK(((snapshot#>>'{audit,createdAt}')::timestamptz=created_at) IS TRUE AND ((snapshot#>>'{audit,updatedAt}')::timestamptz=updated_at) IS TRUE)
);
CREATE TABLE worker_revision (
 worker_id uuid NOT NULL REFERENCES worker_registration(id), version bigint NOT NULL CHECK(version>=0),
 command_kind varchar(24) NOT NULL CHECK(command_kind IN('REGISTER','PROFILE','PERIOD_CLOSED','PERIOD_OPENED')),
 reason varchar(1000), recorded_at timestamptz NOT NULL, recorded_by varchar(200) NOT NULL,
 correlation_id uuid NOT NULL, snapshot jsonb NOT NULL, PRIMARY KEY(worker_id,version),
 CHECK((command_kind='REGISTER' AND version=0) OR (command_kind<>'REGISTER' AND version>0 AND length(btrim(reason))>0))
);
ALTER TABLE worker_registration ADD CONSTRAINT worker_current_revision FOREIGN KEY(id,version)
 REFERENCES worker_revision(worker_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE worker_active_period (
 id uuid PRIMARY KEY, worker_id uuid NOT NULL REFERENCES worker_registration(id),
 active_from date NOT NULL, active_to date, introduced_version bigint NOT NULL CHECK(introduced_version>=0),
 closed_version bigint, UNIQUE(id,worker_id), CHECK(active_to IS NULL OR active_to>active_from),
 CHECK((active_to IS NULL)=(closed_version IS NULL)), CHECK(closed_version IS NULL OR closed_version>=introduced_version),
 FOREIGN KEY(worker_id,introduced_version) REFERENCES worker_revision(worker_id,version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(worker_id,closed_version) REFERENCES worker_revision(worker_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX worker_one_open_period ON worker_active_period(worker_id) WHERE active_to IS NULL;
CREATE TABLE attendance_day (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, worker_id uuid NOT NULL, attendance_date date NOT NULL,
 estate_timezone varchar(100) NOT NULL, worker_code_snapshot varchar(64) NOT NULL, division_id_snapshot uuid,
 worker_version_snapshot bigint NOT NULL CHECK(worker_version_snapshot>=0), active_period_id uuid NOT NULL,
 data_provenance varchar(16) NOT NULL CHECK(data_provenance='SIMULATED'),
 status varchar(16) NOT NULL CHECK(status IN('PRESENT','ABSENT','EXCUSED','VOIDED')),
 version bigint NOT NULL CHECK(version>=0), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 created_by varchar(200) NOT NULL, updated_by varchar(200) NOT NULL, snapshot jsonb NOT NULL,
 UNIQUE(estate_id,worker_id,attendance_date), CHECK(updated_at>=created_at),
 FOREIGN KEY(worker_id,estate_id) REFERENCES worker_registration(id,estate_id),
 FOREIGN KEY(active_period_id,worker_id) REFERENCES worker_active_period(id,worker_id),
 CHECK(snapshot @> jsonb_build_object('id',id,'status',status,
   'audit',jsonb_build_object('version',version,'createdBy',created_by,'updatedBy',updated_by),
   'attribution',jsonb_build_object('estateId',estate_id,'workerRegistrationId',worker_id,'attendanceDate',attendance_date,
    'dataProvenance',data_provenance,'estateTimezone',estate_timezone,'workerCodeSnapshot',worker_code_snapshot,
    'divisionIdSnapshot',division_id_snapshot,'workerVersionSnapshot',worker_version_snapshot,'activePeriodId',active_period_id))),
 CHECK(((snapshot#>>'{audit,createdAt}')::timestamptz=created_at) IS TRUE AND ((snapshot#>>'{audit,updatedAt}')::timestamptz=updated_at) IS TRUE)
);
CREATE TABLE attendance_revision (
 attendance_id uuid NOT NULL REFERENCES attendance_day(id), version bigint NOT NULL CHECK(version>=0),
 command_kind varchar(24) NOT NULL CHECK(command_kind IN('RECORD','CORRECT','VOID','RERECORD')),
 reason varchar(1000), recorded_at timestamptz NOT NULL, recorded_by varchar(200) NOT NULL,
 correlation_id uuid NOT NULL, snapshot jsonb NOT NULL, PRIMARY KEY(attendance_id,version),
 CHECK((command_kind='RECORD' AND version=0) OR (command_kind<>'RECORD' AND version>0 AND length(btrim(reason))>0))
);
ALTER TABLE attendance_day ADD CONSTRAINT attendance_current_revision FOREIGN KEY(id,version)
 REFERENCES attendance_revision(attendance_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE workforce_command (
 actor varchar(200) NOT NULL CHECK(length(btrim(actor))>0), idempotency_key uuid NOT NULL,
 method varchar(8) NOT NULL CHECK(method IN('POST','PATCH')), resource_path varchar(200) NOT NULL,
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 normalization_version integer NOT NULL DEFAULT 1 CHECK(normalization_version=1),
 created_at timestamptz NOT NULL, completed_at timestamptz,
 response_status integer CHECK(response_status IN(200,201)), response_body text, response_location text,
 PRIMARY KEY(actor,idempotency_key)
);

CREATE FUNCTION workforce_immutable_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Workforce evidence is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER worker_revision_immutable BEFORE UPDATE OR DELETE ON worker_revision
 FOR EACH ROW EXECUTE FUNCTION workforce_immutable_evidence();
CREATE TRIGGER attendance_revision_immutable BEFORE UPDATE OR DELETE ON attendance_revision
 FOR EACH ROW EXECUTE FUNCTION workforce_immutable_evidence();

CREATE FUNCTION workforce_worker_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Worker deletion prohibited' USING ERRCODE='55000'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 THEN RAISE EXCEPTION 'Initial worker version must be zero' USING ERRCODE='23514'; END IF;
 ELSE
  IF OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at
   OR ROW(NEW.id,NEW.estate_id,NEW.worker_code,NEW.data_provenance,NEW.created_at,NEW.created_by)
      IS DISTINCT FROM ROW(OLD.id,OLD.estate_id,OLD.worker_code,OLD.data_provenance,OLD.created_at,OLD.created_by) THEN
   RAISE EXCEPTION 'Invalid immutable worker revision' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER workforce_worker_guard BEFORE INSERT OR UPDATE OR DELETE ON worker_registration
 FOR EACH ROW EXECUTE FUNCTION workforce_worker_guard();

CREATE FUNCTION workforce_period_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Active period deletion prohibited' USING ERRCODE='55000'; END IF;
 -- Shared mutex for close versus record/re-record. No extension or cross-service SQL.
 PERFORM id FROM worker_registration WHERE id=NEW.worker_id FOR UPDATE;
 IF TG_OP='UPDATE' AND (OLD.active_to IS NOT NULL OR NEW.active_to IS NULL
   OR ROW(NEW.id,NEW.worker_id,NEW.active_from,NEW.introduced_version)
      IS DISTINCT FROM ROW(OLD.id,OLD.worker_id,OLD.active_from,OLD.introduced_version)) THEN
  RAISE EXCEPTION 'Only an open period may be closed once' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM worker_active_period p WHERE p.worker_id=NEW.worker_id AND p.id<>NEW.id
   AND daterange(p.active_from,p.active_to,'[)') && daterange(NEW.active_from,NEW.active_to,'[)')) THEN
  RAISE EXCEPTION 'Overlapping worker active periods' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER workforce_period_guard BEFORE INSERT OR UPDATE OR DELETE ON worker_active_period
 FOR EACH ROW EXECUTE FUNCTION workforce_period_guard();

CREATE FUNCTION workforce_attendance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE w worker_registration%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Attendance deletion prohibited' USING ERRCODE='55000'; END IF;
 SELECT * INTO w FROM worker_registration WHERE id=NEW.worker_id FOR UPDATE;
 IF w.id IS NULL OR w.estate_id<>NEW.estate_id OR w.data_provenance<>NEW.data_provenance
   OR NEW.worker_version_snapshot>w.version THEN
  RAISE EXCEPTION 'Invalid worker attendance attribution' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>0 OR NEW.status='VOIDED' THEN RAISE EXCEPTION 'Invalid initial attendance' USING ERRCODE='23514'; END IF;
 ELSE
  IF OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at OR NEW.status=OLD.status
    OR ROW(NEW.id,NEW.estate_id,NEW.worker_id,NEW.attendance_date,NEW.data_provenance,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM ROW(OLD.id,OLD.estate_id,OLD.worker_id,OLD.attendance_date,OLD.data_provenance,OLD.created_at,OLD.created_by)
    OR (OLD.status<>'VOIDED' AND ROW(NEW.estate_timezone,NEW.worker_code_snapshot,NEW.division_id_snapshot,NEW.worker_version_snapshot,NEW.active_period_id)
       IS DISTINCT FROM ROW(OLD.estate_timezone,OLD.worker_code_snapshot,OLD.division_id_snapshot,OLD.worker_version_snapshot,OLD.active_period_id)) THEN
   RAISE EXCEPTION 'Invalid immutable daily revision' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.status<>'VOIDED' AND NOT EXISTS(SELECT 1 FROM worker_active_period p
   WHERE p.id=NEW.active_period_id AND p.worker_id=NEW.worker_id
    AND NEW.attendance_date>=p.active_from AND (p.active_to IS NULL OR NEW.attendance_date<p.active_to)) THEN
  RAISE EXCEPTION 'Attendance must reference an eligible active period' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER workforce_attendance_guard BEFORE INSERT OR UPDATE OR DELETE ON attendance_day
 FOR EACH ROW EXECUTE FUNCTION workforce_attendance_guard();

CREATE FUNCTION workforce_period_consistency() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE target uuid; stored jsonb; actual jsonb;
BEGIN
 IF TG_TABLE_NAME='worker_registration' THEN target:=NEW.id; ELSE target:=NEW.worker_id; END IF;
 SELECT jsonb_agg(e ORDER BY e->>'id') INTO stored FROM worker_registration w,
  LATERAL jsonb_array_elements(w.snapshot->'activePeriods') e WHERE w.id=target;
 SELECT jsonb_agg(jsonb_build_object('id',p.id,'activeFrom',p.active_from,'activeTo',p.active_to,
   'introducedVersion',p.introduced_version,'closedVersion',p.closed_version) ORDER BY p.id::text)
  INTO actual FROM worker_active_period p WHERE p.worker_id=target;
 IF actual IS NULL OR stored IS DISTINCT FROM actual THEN
  RAISE EXCEPTION 'Worker active-period snapshot mismatch' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM attendance_day a JOIN worker_active_period p ON p.id=a.active_period_id
   WHERE a.worker_id=target AND a.status<>'VOIDED' AND
    (a.attendance_date<p.active_from OR (p.active_to IS NOT NULL AND a.attendance_date>=p.active_to))) THEN
  RAISE EXCEPTION 'Period closure would invalidate retained attendance' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER workforce_period_consistency AFTER INSERT OR UPDATE ON worker_active_period
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION workforce_period_consistency();
CREATE CONSTRAINT TRIGGER workforce_root_consistency AFTER INSERT OR UPDATE ON worker_registration
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION workforce_period_consistency();

CREATE FUNCTION workforce_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.completed_at IS NOT NULL THEN
  RAISE EXCEPTION 'Completed workforce receipt is immutable' USING ERRCODE='55000';
 END IF;
 IF ROW(NEW.actor,NEW.idempotency_key,NEW.method,NEW.resource_path,NEW.request_hash,NEW.normalization_version,NEW.created_at)
  IS DISTINCT FROM ROW(OLD.actor,OLD.idempotency_key,OLD.method,OLD.resource_path,OLD.request_hash,OLD.normalization_version,OLD.created_at) THEN
  RAISE EXCEPTION 'Workforce command identity is immutable' USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER workforce_receipt_guard BEFORE UPDATE OR DELETE ON workforce_command
 FOR EACH ROW EXECUTE FUNCTION workforce_receipt_guard();
CREATE FUNCTION workforce_receipt_complete() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE receipt workforce_command%ROWTYPE;
BEGIN
 SELECT * INTO receipt FROM workforce_command WHERE actor=NEW.actor AND idempotency_key=NEW.idempotency_key;
 IF receipt.completed_at IS NULL OR receipt.response_status IS NULL OR receipt.response_body IS NULL
   OR (receipt.response_status=201 AND (receipt.response_location IS NULL OR length(receipt.response_location)=0)) THEN
  RAISE EXCEPTION 'Incomplete workforce command cannot commit' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER workforce_receipt_complete AFTER INSERT OR UPDATE ON workforce_command
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION workforce_receipt_complete();

CREATE INDEX worker_scope_code ON worker_registration(estate_id,data_provenance,worker_code,id);
CREATE INDEX worker_division_code ON worker_registration(estate_id,division_id,worker_code,id);
CREATE INDEX worker_period_dates ON worker_active_period(worker_id,active_from,active_to);
CREATE INDEX attendance_scope_date ON attendance_day(estate_id,data_provenance,attendance_date,id);
CREATE INDEX attendance_worker_date ON attendance_day(worker_id,attendance_date,id);
DO $$
DECLARE service_schema text:=current_schema();
BEGIN
 -- V3 already established/validated the restricted runtime role; no credentials here.
 EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I.worker_registration,%I.worker_active_period,%I.attendance_day,%I.workforce_command TO agrosphere_operations_app',service_schema,service_schema,service_schema,service_schema);
 EXECUTE format('GRANT SELECT,INSERT ON %I.worker_revision,%I.attendance_revision TO agrosphere_operations_app',service_schema,service_schema);
END $$;
