-- Tenants and API keys (kockpit-backend-tenants). Same migration location as V1__users.sql
-- (kockpit-backend-authentication-local): versions are shared across both jars.

-- A tenant is one Kockpit domain, owned by the user who created it; its members are the
-- users in group "tenant:<slug>".
create table tenants (
    id         uuid        primary key,
    slug       varchar(30) not null unique,
    owner_id   uuid        not null references users (id) on delete cascade,
    created_at timestamptz not null default now()
);

create index tenants_owner_idx on tenants (owner_id);

-- The envs the tenant's apps may send to (one manifest each).
create table tenant_envs (
    tenant_id uuid        not null references tenants (id) on delete cascade,
    env       varchar(15) not null,
    primary key (tenant_id, env)
);

-- Only the key's SHA-256 is stored; the key itself is shown once, at creation.
create table api_keys (
    id           uuid         primary key,
    tenant_id    uuid         not null references tenants (id) on delete cascade,
    name         varchar(100) not null,
    prefix       varchar(16)  not null,
    key_hash     char(64)     not null unique,
    daily_quota  integer      not null check (daily_quota >= 0),
    created_at   timestamptz  not null default now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);

create index api_keys_tenant_idx on api_keys (tenant_id);

-- Audits accepted per key and UTC day, counted by the ingestion gateway.
create table usage_daily (
    api_key_id uuid   not null references api_keys (id) on delete cascade,
    day        date   not null,
    audits     bigint not null default 0,
    primary key (api_key_id, day)
);
