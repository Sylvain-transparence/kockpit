-- Invitations to join a tenant: its owner creates one, sends the link (kockpit sends no mail), the invited
-- account accepts it and joins the tenant's group (tenant:<slug>), which gives it the tenant's audits.
-- Like API keys, only the code's SHA-256 is stored: the code itself is shown once, at creation.
create table tenant_invitations (
    id          uuid         primary key,
    tenant_id   uuid         not null references tenants (id) on delete cascade,
    code_hash   char(64)     not null unique,
    -- optional: only this account (lower-cased email) may accept it
    email       varchar(254),
    created_by  uuid         references users (id) on delete set null,
    created_at  timestamptz  not null default now(),
    expires_at  timestamptz  not null,
    accepted_by uuid         references users (id) on delete set null,
    accepted_at timestamptz,
    revoked_at  timestamptz
);

create index tenant_invitations_tenant_idx on tenant_invitations (tenant_id);
