create table if not exists pending_commands (
    id uuid primary key,
    account_id text not null,
    kind text not null,
    target_id text not null,
    spec text not null,
    state_digest text not null,
    impact text not null,
    status text not null,
    created_at timestamptz not null,
    expires_at timestamptz not null
);
create unique index if not exists pending_commands_one_executing
    on pending_commands (target_id) where status = 'EXECUTING';
create unique index if not exists pending_commands_one_pending
    on pending_commands (account_id, kind, target_id) where status = 'PENDING';

create table if not exists equipment_events (
    event_id text primary key,
    equipment_id text not null,
    code text not null,
    value integer,
    occurred_at timestamptz not null
);
create index if not exists equipment_events_equipment_time on equipment_events (equipment_id, occurred_at);
create index if not exists equipment_events_time on equipment_events (occurred_at);

create table if not exists incidents (
    id uuid primary key,
    equipment_id text not null,
    kind text not null,
    severity text not null,
    opened_at timestamptz not null,
    closed_at timestamptz
);
create unique index if not exists incidents_one_open
    on incidents (equipment_id, kind) where closed_at is null;

create table if not exists maintenance_requests (
    id uuid primary key,
    equipment_id text not null,
    summary text not null,
    opened_at timestamptz not null,
    closed_at timestamptz
);
create unique index if not exists maintenance_requests_one_open
    on maintenance_requests (equipment_id) where closed_at is null;

create table if not exists command_audit (
    id bigint generated always as identity primary key,
    command_id uuid not null,
    account_id text not null,
    spec text not null,
    result text not null,
    recorded_at timestamptz not null
);
create index if not exists command_audit_command on command_audit (command_id);

-- IngestEventService.CODE와 같은 형식이다. PostgreSQL에는 add constraint if not exists가 없어 중복 예외를 삼킨다.
-- 스크립트 분리기가 $$ 인용을 모르므로 본문을 작은따옴표 문자열로 쓴다.
do 'begin
    alter table equipment_events add constraint equipment_events_code_format
        check (code ~ ''^[A-Z][A-Z0-9_]{0,39}$'');
exception when duplicate_object then null;
end';
