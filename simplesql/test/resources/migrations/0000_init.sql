-- prev: base

create table user_account (
    user_id uuid primary key not null,
    user_name text not null unique,
    primary_email text not null unique
);

-- down:

drop table user_account;
