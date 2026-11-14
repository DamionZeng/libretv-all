-- 暴风资源元数据采集系统 / PostgreSQL 15
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS videos (
    id                 BIGINT PRIMARY KEY,
    name               TEXT        NOT NULL,
    sub_name           TEXT,
    en_name            TEXT,
    type_id            INTEGER,
    type_id_1          INTEGER,
    type_name          TEXT,
    vod_class          TEXT,
    genres             TEXT[]      NOT NULL DEFAULT '{}',
    year               SMALLINT,
    area               TEXT,
    lang               TEXT,
    remarks            TEXT,
    score              NUMERIC(3,1),
    score_all          INTEGER,
    score_num          INTEGER,
    douban_id          BIGINT,
    douban_score       NUMERIC(3,1),
    actor              TEXT,
    director           TEXT,
    writer             TEXT,
    blurb              TEXT,
    content            TEXT,
    pic_source         TEXT,
    poster_path        TEXT,
    hits               BIGINT,
    hits_day           INTEGER,
    hits_week          INTEGER,
    hits_month         INTEGER,
    serial             INTEGER,
    total              INTEGER,
    is_end             BOOLEAN     NOT NULL DEFAULT FALSE,
    pubdate            TEXT,
    duration           TEXT,
    episode_count      INTEGER,
    source_updated_at  TIMESTAMPTZ,
    fetched_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS videos_catalog_idx
    ON videos (type_id_1, year, source_updated_at DESC)
    WHERE year IS NOT NULL AND pic_source IS NOT NULL;

CREATE INDEX IF NOT EXISTS videos_type_idx
    ON videos (type_id, year, score DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS videos_score_idx
    ON videos (score DESC NULLS LAST, hits DESC NULLS LAST)
    WHERE year IS NOT NULL AND pic_source IS NOT NULL;

CREATE INDEX IF NOT EXISTS videos_hits_idx
    ON videos (hits DESC NULLS LAST)
    WHERE year IS NOT NULL AND pic_source IS NOT NULL;

CREATE INDEX IF NOT EXISTS videos_updated_idx
    ON videos (source_updated_at DESC);

CREATE INDEX IF NOT EXISTS videos_name_trgm_idx
    ON videos USING gin (name gin_trgm_ops);

CREATE TABLE IF NOT EXISTS sync_state (
    id                  SMALLINT PRIMARY KEY,
    active_domain       TEXT,
    page_count          INTEGER,
    full_page           INTEGER     NOT NULL DEFAULT 0,
    incremental_cursor  TIMESTAMPTZ,
    last_incremental_at TIMESTAMPTZ,
    last_full_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO sync_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
ALTER TABLE sync_state ADD COLUMN IF NOT EXISTS page_count INTEGER;

CREATE TABLE IF NOT EXISTS sync_runs (
    id              BIGSERIAL PRIMARY KEY,
    mode            TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'running',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    pages_done      INTEGER     NOT NULL DEFAULT 0,
    items_fetched   INTEGER     NOT NULL DEFAULT 0,
    items_upserted  INTEGER     NOT NULL DEFAULT 0,
    items_failed    INTEGER     NOT NULL DEFAULT 0,
    last_page       INTEGER,
    error           TEXT
);

CREATE INDEX IF NOT EXISTS sync_runs_started_idx ON sync_runs (started_at DESC);
ALTER TABLE sync_runs ADD COLUMN IF NOT EXISTS items_inserted INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_runs ADD COLUMN IF NOT EXISTS items_updated INTEGER NOT NULL DEFAULT 0;

-- 调度器配置（后台管理页可在线修改，重启后仍然生效）
CREATE TABLE IF NOT EXISTS sync_settings (
    id                SMALLINT PRIMARY KEY,
    scheduler_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    interval_minutes  INTEGER     NOT NULL DEFAULT 1440,
    requested_mode    TEXT,
    requested_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO sync_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
