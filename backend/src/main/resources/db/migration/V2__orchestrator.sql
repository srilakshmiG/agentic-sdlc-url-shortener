CREATE TABLE runs (
    id                  UUID PRIMARY KEY,
    title               VARCHAR(200) NOT NULL,
    requirement         TEXT         NOT NULL,
    requirement_version INT          NOT NULL DEFAULT 1,
    scenario            VARCHAR(40)  NOT NULL,
    mode                VARCHAR(20)  NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    plan_version        INT          NOT NULL DEFAULT 1,
    faults              TEXT         NOT NULL DEFAULT '[]',
    stop_reason         TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ
);

CREATE TABLE stages (
    run_id          UUID         NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    node_id         VARCHAR(60)  NOT NULL,
    spec            TEXT         NOT NULL,
    active_agent    VARCHAR(60)  NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    attempt         INT          NOT NULL DEFAULT 0,
    retries         INT          NOT NULL DEFAULT 0,
    rejections      INT          NOT NULL DEFAULT 0,
    epoch           INT          NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    first_failed_at TIMESTAMPTZ,
    active_ms       BIGINT       NOT NULL DEFAULT 0,
    last_error      TEXT,
    feedback        TEXT,
    completed_seq   BIGINT,
    PRIMARY KEY (run_id, node_id)
);

CREATE TABLE artifacts (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID         NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    name         VARCHAR(80)  NOT NULL,
    version      INT          NOT NULL,
    stage_id     VARCHAR(60)  NOT NULL,
    content_type VARCHAR(40)  NOT NULL,
    content      TEXT         NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    derived_from TEXT         NOT NULL DEFAULT '[]',
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (run_id, name, version)
);

CREATE TABLE approvals (
    id         UUID PRIMARY KEY,
    run_id     UUID         NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    stage_id   VARCHAR(60)  NOT NULL,
    kind       VARCHAR(20)  NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    summary    TEXT         NOT NULL,
    questions  TEXT         NOT NULL DEFAULT '[]',
    answers    TEXT         NOT NULL DEFAULT '{}',
    decided_by VARCHAR(80),
    comment    TEXT,
    created_at TIMESTAMPTZ  NOT NULL,
    decided_at TIMESTAMPTZ
);

CREATE TABLE decisions (
    id        BIGSERIAL PRIMARY KEY,
    run_id    UUID         NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    stage_id  VARCHAR(60),
    actor     VARCHAR(80)  NOT NULL,
    decision  TEXT         NOT NULL,
    rationale TEXT         NOT NULL,
    inputs    TEXT         NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE audit_events (
    seq        BIGSERIAL PRIMARY KEY,
    run_id     UUID         NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    ts         TIMESTAMPTZ  NOT NULL,
    actor      VARCHAR(80)  NOT NULL,
    type       VARCHAR(40)  NOT NULL,
    stage_id   VARCHAR(60),
    payload    TEXT         NOT NULL,
    prev_hash  VARCHAR(64)  NOT NULL,
    hash       VARCHAR(64)  NOT NULL
);

CREATE INDEX idx_audit_run ON audit_events (run_id, seq);
CREATE INDEX idx_artifacts_run ON artifacts (run_id, name, version DESC);
CREATE INDEX idx_approvals_run ON approvals (run_id, created_at);
