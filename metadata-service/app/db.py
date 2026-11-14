from __future__ import annotations

import logging
from pathlib import Path

import asyncpg

from app.config import BASE_DIR, settings

log = logging.getLogger(__name__)

_pool: asyncpg.Pool | None = None
SCHEMA_FILE = BASE_DIR / "sql" / "schema.sql"


async def init_pool(min_size: int = 1, max_size: int = 10) -> asyncpg.Pool:
    global _pool
    if _pool is None:
        _pool = await asyncpg.create_pool(
            dsn=settings.database_url,
            min_size=min_size,
            max_size=max_size,
            command_timeout=60,
            server_settings={"application_name": "libretv-metadata"},
        )
    return _pool


async def get_pool() -> asyncpg.Pool:
    if _pool is None:
        return await init_pool()
    return _pool


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


async def ensure_schema(pool: asyncpg.Pool | None = None) -> None:
    pool = pool or await get_pool()
    sql = Path(SCHEMA_FILE).read_text(encoding="utf-8")
    async with pool.acquire() as conn:
        await conn.execute(sql)
    log.info("database schema ready")
