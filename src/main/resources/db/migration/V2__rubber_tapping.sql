CREATE TABLE tapping_schedule (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, block_id uuid NOT NULL,
 name varchar(200) NOT NULL, recurrence_rule varchar(40) NOT NULL,
 planned_tree_count integer NOT NULL CHECK(planned_tree_count>0),
 effective_from date NOT NULL, effective_to date CHECK(effective_to>=effective_from),
 status varchar(20) NOT NULL CHECK(status IN ('DRAFT','ACTIVE','SUSPENDED','CLOSED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL CHECK(version>=0),
 created_by varchar(200) NOT NULL, updated_by varchar(200) NOT NULL
);
CREATE INDEX tapping_schedule_scope ON tapping_schedule(estate_id,block_id,status);
CREATE TABLE tapping_assignment (
 id uuid PRIMARY KEY, schedule_id uuid REFERENCES tapping_schedule(id), estate_id uuid NOT NULL, block_id uuid NOT NULL,
 worker_id varchar(200) NOT NULL, tapping_date date NOT NULL,
 planned_tree_count integer NOT NULL CHECK(planned_tree_count>0),
 status varchar(20) NOT NULL CHECK(status IN ('PLANNED','IN_PROGRESS','COMPLETED','MISSED','CANCELLED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL CHECK(version>=0),
 created_by varchar(200) NOT NULL, updated_by varchar(200) NOT NULL,
 UNIQUE(schedule_id,tapping_date)
);
CREATE UNIQUE INDEX tapping_assignment_worker_occurrence ON tapping_assignment(worker_id,block_id,tapping_date) WHERE status<>'CANCELLED';
CREATE INDEX tapping_assignment_scope_date ON tapping_assignment(estate_id,tapping_date,block_id,status);
CREATE INDEX tapping_assignment_worker_date ON tapping_assignment(worker_id,tapping_date);
CREATE TABLE tapping_record (
 id uuid PRIMARY KEY, assignment_id uuid NOT NULL UNIQUE REFERENCES tapping_assignment(id),
 worker_id varchar(200) NOT NULL,estate_id uuid NOT NULL,block_id uuid NOT NULL,tapping_date date NOT NULL,
 planned_tree_count integer NOT NULL CHECK(planned_tree_count>0),
 actual_tree_count integer NOT NULL CHECK(actual_tree_count>=0 AND actual_tree_count<=planned_tree_count),
 started_at timestamptz NOT NULL,completed_at timestamptz CHECK(completed_at>=started_at),notes varchar(2000),
 created_by varchar(200) NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL
);
CREATE INDEX tapping_record_scope_date ON tapping_record(estate_id,tapping_date,worker_id,block_id);
CREATE TABLE tapping_missed_reason (
 assignment_id uuid PRIMARY KEY REFERENCES tapping_assignment(id),
 reason_code varchar(30) NOT NULL CHECK(reason_code IN ('RAIN','WORKER_UNAVAILABLE','TREE_RESTING','ACCESS_RESTRICTED','OTHER')),
 remarks varchar(2000),recorded_at timestamptz NOT NULL,recorded_by varchar(200) NOT NULL
);
ALTER TABLE outbox_event ADD COLUMN delivery_status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(delivery_status IN ('PENDING','PUBLISHED','FAILED'));
ALTER TABLE outbox_event ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX outbox_due ON outbox_event(next_attempt_at,occurred_at) WHERE delivery_status='PENDING';

UPDATE outbox_event SET delivery_status='PUBLISHED' WHERE published_at IS NOT NULL;
