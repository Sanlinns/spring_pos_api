CREATE TABLE login_sessions (
    id UUID PRIMARY KEY,
    account_type VARCHAR(10) NOT NULL CHECK (account_type IN ('USER', 'STAFF')),
    account_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL REFERENCES shops(id),
    device_id VARCHAR(128) NOT NULL,
    device_name VARCHAR(200) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(50)
);
CREATE INDEX login_sessions_shop_device ON login_sessions(shop_id, device_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX login_sessions_account ON login_sessions(account_type, account_id);
CREATE TABLE consumed_refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES login_sessions(id) ON DELETE CASCADE,
    consumed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX consumed_refresh_session ON consumed_refresh_tokens(session_id);
