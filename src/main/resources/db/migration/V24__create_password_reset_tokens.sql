create table password_reset_tokens (
    id bigserial primary key,
    user_id bigint not null,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    used boolean not null default false,
    created_at timestamptz not null default current_timestamp,
    constraint fk_password_reset_tokens_user
        foreign key (user_id) references users(id) on delete cascade
);

-- The unique constraint already supplies a btree index for token_hash.
create index idx_password_reset_tokens_user_id on password_reset_tokens(user_id);
create index idx_password_reset_tokens_expires_at on password_reset_tokens(expires_at);
