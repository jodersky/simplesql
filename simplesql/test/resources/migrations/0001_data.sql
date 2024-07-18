-- prev: 0000_init.sql

insert into user_account (user_id, user_name, primary_email)
values (1, "admin", "admin@example.org"), (2, "admin2", "admin2@example.org")

-- down:

delete from user_account where user_id=1;
