-- 暴风资源元数据采集系统 / MySQL 5.7+
CREATE TABLE IF NOT EXISTS videos (
    id                 BIGINT       NOT NULL,
    name               VARCHAR(300) NOT NULL,
    sub_name           VARCHAR(300),
    en_name            VARCHAR(300),
    type_id            INT,
    type_id_1          INT,
    type_name          VARCHAR(100),
    vod_class          VARCHAR(300),
    genres             JSON,
    year               SMALLINT,
    area               VARCHAR(100),
    lang               VARCHAR(100),
    remarks            VARCHAR(200),
    score              DECIMAL(3,1),
    score_all          INT,
    score_num          INT,
    douban_id          BIGINT,
    douban_score       DECIMAL(3,1),
    actor              VARCHAR(2000),
    director           VARCHAR(500),
    writer             VARCHAR(500),
    blurb              VARCHAR(500),
    content            MEDIUMTEXT,
    pic_source         VARCHAR(1024),
    poster_path        VARCHAR(255),
    hits               BIGINT,
    hits_day           INT,
    hits_week          INT,
    hits_month         INT,
    serial             INT,
    total              INT,
    is_end             BOOLEAN      NOT NULL DEFAULT FALSE,
    pubdate            VARCHAR(100),
    duration           VARCHAR(100),
    episode_count      INT,
    source_updated_at  DATETIME,
    fetched_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY videos_catalog_idx (type_id_1, year, source_updated_at),
    KEY videos_type_idx (type_id, year, score),
    KEY videos_score_idx (score, hits),
    KEY videos_hits_idx (hits),
    KEY videos_updated_idx (source_updated_at),
    KEY videos_name_idx (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sync_state (
    id                  TINYINT PRIMARY KEY,
    active_domain       VARCHAR(255),
    page_count          INT,
    full_page           INT      NOT NULL DEFAULT 0,
    incremental_cursor  DATETIME,
    last_incremental_at DATETIME,
    last_full_at        DATETIME,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO sync_state (id) VALUES (1);

CREATE TABLE IF NOT EXISTS sync_runs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    mode            VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'running',
    started_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     DATETIME,
    pages_done      INT         NOT NULL DEFAULT 0,
    items_fetched   INT         NOT NULL DEFAULT 0,
    items_upserted  INT         NOT NULL DEFAULT 0,
    items_failed    INT         NOT NULL DEFAULT 0,
    last_page       INT,
    error           TEXT,
    items_inserted  INT         NOT NULL DEFAULT 0,
    items_updated   INT         NOT NULL DEFAULT 0,
    KEY sync_runs_started_idx (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 调度器配置（后台管理页可在线修改，重启后仍然生效）
CREATE TABLE IF NOT EXISTS sync_settings (
    id                TINYINT PRIMARY KEY,
    scheduler_enabled BOOLEAN    NOT NULL DEFAULT TRUE,
    interval_minutes  INT        NOT NULL DEFAULT 1440,
    requested_mode    VARCHAR(20),
    requested_at      DATETIME,
    updated_at        DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO sync_settings (id) VALUES (1);

-- 片源列表（后台维护，App 启动时拉取并自动导入）
CREATE TABLE IF NOT EXISTS sources (
    id          INT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    url         VARCHAR(500) NOT NULL,
    detail      VARCHAR(500) NOT NULL DEFAULT '',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 100,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY sources_url_key (url(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO sources (name, url, detail, sort_order) VALUES
    ('🎬暴风资源', 'https://bfzyapi.com/api.php/provide/vod', 'https://bfzy.tv', 10);
