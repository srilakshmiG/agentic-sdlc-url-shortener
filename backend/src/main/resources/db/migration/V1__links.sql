CREATE TABLE links (
    code            VARCHAR(32)  PRIMARY KEY,
    target_url      TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    disabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    click_count     BIGINT       NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(80)  UNIQUE
);

CREATE INDEX idx_links_created_at ON links (created_at DESC);

CREATE TABLE click_events (
    event_id    UUID         PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL REFERENCES links (code) ON DELETE CASCADE,
    clicked_at  TIMESTAMPTZ  NOT NULL,
    referrer    VARCHAR(255) NOT NULL DEFAULT 'direct',
    visitor_hash VARCHAR(64) NOT NULL
);

CREATE INDEX idx_click_events_code_time ON click_events (code, clicked_at DESC);
