alter table users
    add column if not exists email varchar(255),
    add column if not exists phone varchar(30);

do $$
begin
    if exists (
        select lower(trim(email))
        from users
        where email is not null
          and trim(email) <> ''
        group by lower(trim(email))
        having count(*) > 1
    ) then
        raise exception 'Cannot create unique user email index: duplicate case-insensitive emails exist';
    end if;
end
$$;

create unique index if not exists ux_users_email_lower
    on users (lower(email))
    where email is not null;
