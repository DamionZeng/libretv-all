from __future__ import annotations

import logging
from pathlib import Path
from urllib.parse import unquote, urlparse

import aiomysql
from aiomysql.cursors import DictCursor

from app.config import BASE_DIR, settings

log = logging.getLogger(__name__)

_pool: "Database | None" = None
SCHEMA_FILE = BASE_DIR / "sql" / "schema.sql"


def dsn_parts(dsn: str) -> dict:
    """解析 mysql://user:pass@host:port/db 形式的连接串。"""
    parsed = urlparse(dsn)
    scheme = parsed.scheme.split("+")[0]
    if scheme not in {"mysql", ""}:
        raise ValueError(f"仅支持 mysql:// 连接串，当前为 {parsed.scheme}://")
    return {
        "host": parsed.hostname or "127.0.0.1",
        "port": parsed.port or 3306,
        "user": unquote(parsed.username or "root"),
        "password": unquote(parsed.password or ""),
        "db": unquote((parsed.path or "/").lstrip("/")) or None,
    }


class Connection:
    """把 aiomysql 游标操作收敛成 fetch / fetchrow / fetchval / execute 四个方法。"""

    def __init__(self, raw: aiomysql.Connection):
        self.raw = raw

    async def fetch(self, sql: str, *args) -> list[dict]:
        async with self.raw.cursor(DictCursor) as cur:
            await cur.execute(sql, args or None)
            return list(await cur.fetchall())

    async def fetchrow(self, sql: str, *args) -> dict | None:
        rows = await self.fetch(sql, *args)
        return rows[0] if rows else None

    async def fetchval(self, sql: str, *args):
        row = await self.fetchrow(sql, *args)
        return None if row is None else next(iter(row.values()))

    async def execute(self, sql: str, *args) -> int:
        async with self.raw.cursor() as cur:
            await cur.execute(sql, args or None)
            return cur.rowcount

    async def executemany(self, sql: str, rows: list[tuple]) -> int:
        if not rows:
            return 0
        async with self.raw.cursor() as cur:
            await cur.executemany(sql, rows)
            return cur.rowcount

    async def execute_insert(self, sql: str, *args) -> int:
        """执行 INSERT 并返回自增主键（MySQL 没有 RETURNING）。"""
        async with self.raw.cursor() as cur:
            await cur.execute(sql, args or None)
            return int(cur.lastrowid or 0)

    def transaction(self) -> "_Transaction":
        return _Transaction(self)


class _Transaction:
    """显式 START TRANSACTION / COMMIT，对 autocommit 连接同样有效。"""

    def __init__(self, conn: Connection):
        self.conn = conn

    async def __aenter__(self) -> Connection:
        await self.conn.execute("START TRANSACTION")
        return self.conn

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.conn.execute("ROLLBACK" if exc_type else "COMMIT")


class _Acquired:
    def __init__(self, pool: aiomysql.Pool):
        self._pool = pool
        self._raw: aiomysql.Connection | None = None

    async def __aenter__(self) -> Connection:
        self._raw = await self._pool.acquire()
        return Connection(self._raw)

    async def __aexit__(self, exc_type, exc, tb) -> None:
        if self._raw is not None:
            self._pool.release(self._raw)
            self._raw = None


class Database:
    def __init__(self, pool: aiomysql.Pool):
        self.raw_pool = pool

    def acquire(self) -> _Acquired:
        return _Acquired(self.raw_pool)

    async def fetch(self, sql: str, *args) -> list[dict]:
        async with self.acquire() as conn:
            return await conn.fetch(sql, *args)

    async def fetchrow(self, sql: str, *args) -> dict | None:
        async with self.acquire() as conn:
            return await conn.fetchrow(sql, *args)

    async def fetchval(self, sql: str, *args):
        async with self.acquire() as conn:
            return await conn.fetchval(sql, *args)

    async def execute(self, sql: str, *args) -> int:
        async with self.acquire() as conn:
            return await conn.execute(sql, *args)

    async def executemany(self, sql: str, rows: list[tuple]) -> int:
        async with self.acquire() as conn:
            return await conn.executemany(sql, rows)

    async def close(self) -> None:
        self.raw_pool.close()
        await self.raw_pool.wait_closed()


async def init_pool(min_size: int = 1, max_size: int = 10) -> Database:
    global _pool
    if _pool is None:
        parts = dsn_parts(settings.database_url)
        pool = await aiomysql.create_pool(
            host=parts["host"],
            port=parts["port"],
            user=parts["user"],
            password=parts["password"],
            db=parts["db"],
            minsize=min_size,
            maxsize=max_size,
            autocommit=True,
            charset="utf8mb4",
            init_command="SET time_zone='+00:00'",
        )
        _pool = Database(pool)
        log.info("mysql pool ready: %s:%s/%s", parts["host"], parts["port"], parts["db"])
    return _pool


async def get_pool() -> Database:
    if _pool is None:
        return await init_pool()
    return _pool


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


async def ensure_schema(pool: Database | None = None) -> None:
    """逐条执行 schema.sql；DDL 幂等，单条失败只记录不中断。"""
    pool = pool or await get_pool()
    raw = Path(SCHEMA_FILE).read_text(encoding="utf-8-sig")
    # 先去掉 -- 注释行，再按分号切分，避免整段 DDL 被当成注释跳过
    body = "\n".join(line for line in raw.splitlines() if not line.strip().startswith("--"))
    async with pool.acquire() as conn:
        for statement in (part.strip() for part in body.split(";")):
            if not statement:
                continue
            try:
                await conn.execute(statement)
            except Exception as exc:  # noqa: BLE001 - 旧表已存在等场景允许跳过
                log.warning("schema statement skipped: %s", exc)
    log.info("database schema ready")
