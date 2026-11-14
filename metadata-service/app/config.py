from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")


def _str(name: str, default: str = "") -> str:
    value = os.getenv(name)
    return default if value is None else value.strip()


def _int(name: str, default: int) -> int:
    try:
        return int(_str(name, str(default)))
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(_str(name, str(default)))
    except ValueError:
        return default


def _bool(name: str, default: bool) -> bool:
    return _str(name, "true" if default else "false").lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    database_url: str
    source_domains: tuple[str, ...]
    site_page_size: int
    request_rps: float
    source_concurrency: int
    http_timeout: float
    http_retries: int
    api_host: str
    api_port: int
    public_base_url: str
    admin_token: str
    sync_enabled: bool
    sync_interval_minutes: int
    incremental_max_pages: int
    image_cache_dir: Path
    image_max_bytes: int
    log_file: Path


def _load() -> Settings:
    domains = tuple(
        d.strip().rstrip("/")
        for d in _str("SOURCE_DOMAINS", "https://bfzy.tv").split(",")
        if d.strip()
    )
    cache_dir = Path(_str("IMAGE_CACHE_DIR", "./data/posters"))
    if not cache_dir.is_absolute():
        cache_dir = (BASE_DIR / cache_dir).resolve()
    log_file = Path(_str("LOG_FILE", "./data/service.log"))
    if not log_file.is_absolute():
        log_file = (BASE_DIR / log_file).resolve()
    return Settings(
        database_url=_str(
            "DATABASE_URL",
            "mysql://root:root@127.0.0.1:3306/libretv_metadata",
        ),
        source_domains=domains or ("https://bfzy.tv",),
        site_page_size=max(1, min(_int("SITE_PAGE_SIZE", 50), 50)),
        request_rps=max(0.2, _float("REQUEST_RPS", 3.0)),
        source_concurrency=max(1, _int("SOURCE_CONCURRENCY", 4)),
        http_timeout=max(3.0, _float("HTTP_TIMEOUT", 20.0)),
        http_retries=max(1, _int("HTTP_RETRIES", 3)),
        api_host=_str("API_HOST", "0.0.0.0"),
        api_port=_int("API_PORT", 8080),
        public_base_url=_str("PUBLIC_BASE_URL").rstrip("/"),
        admin_token=_str("ADMIN_TOKEN"),
        sync_enabled=_bool("SYNC_ENABLED", True),
        sync_interval_minutes=max(1, _int("SYNC_INTERVAL_MINUTES", 30)),
        incremental_max_pages=max(1, _int("INCREMENTAL_MAX_PAGES", 20)),
        image_cache_dir=cache_dir,
        image_max_bytes=max(1024 * 64, _int("IMAGE_MAX_BYTES", 8 * 1024 * 1024)),
        log_file=log_file,
    )


settings = _load()
