-- Local console users. Emails are unique case-insensitively and stored lower-cased.
create table users (
    id            uuid         primary key,
    email         varchar(254) not null,
    password_hash varchar(100) not null,
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now(),
    last_login_at timestamptz
);

create unique index users_email_uk on users (lower(email));

-- A user's groups: what manifest access is evaluated against (tenant groups, admin group).
create table user_groups (
    user_id    uuid         not null references users (id) on delete cascade,
    group_name varchar(200) not null,
    primary key (user_id, group_name)
);
