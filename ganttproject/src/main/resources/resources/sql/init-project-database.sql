create table if not exists Task (
    uid                     varchar                 not null,
    num                     integer                 not null,
    name                    varchar                 not null,
    color                   varchar                     null,
    shape                   varchar                     null,
    is_milestone            boolean                 not null DEFAULT false,
    is_project_task         boolean                 not null DEFAULT false,
    start_date              date                    not null,
    end_date                date                        null,
    duration                integer                 not null,
    completion              integer                     null,
    earliest_start_date     date                        null,
    priority                varchar                 not null DEFAULT '1',
    web_link                varchar                     null,
    cost_manual_value       numeric(1000, 2)          null,
    is_cost_calculated      boolean                     null,
    notes                   varchar                     null,
    cost                    numeric(1000, 2)            null,

    primary key (uid)
);

create table if not exists TaskCustomColumn(
    uid varchar not null,
    column_id varchar not null,
    column_value varchar,
    primary key (uid, column_id)
);

create table if not exists TaskDependency (
    dependee_uid    varchar     not null,
    dependant_uid   varchar     not null,
    type            varchar     not null,
    lag             integer     not null,
    hardness        varchar     not null,

    primary key (dependee_uid, dependant_uid),
--     foreign key (dependee_uid)  references Task(uid),
--     foreign key (dependant_uid) references Task(uid),
    check (dependee_uid <> dependant_uid)
);

create table if not exists LogRecord (
  id                    integer generated by default as identity    not null,
  local_txn_id          integer     not null,
  operation_dto_json    varchar     not null,

  primary key (id)
);

DROP VIEW if exists TaskViewForComputedColumns;
CREATE VIEW TaskViewForComputedColumns AS
    SELECT
    uid,
    num as id,
    name,
    color,
    is_milestone,
    is_project_task,
    start_date,
    CAST('2022-02-24' AS date) AS end_date,
    duration,
    completion,
    earliest_start_date,
    priority,
    web_link,
    cost_manual_value,
    is_cost_calculated,
    notes,
    42.0 AS cost
    FROM Task;
