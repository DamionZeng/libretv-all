#!/usr/bin/env python3
"""把 PostgreSQL 中的数据一次性迁移到 MySQL。

用法：
    python scripts/sync_db.py \
        --source "postgresql://postgres:postgres@127.0.0.1:5432/libretv_metadata" \
        --target "mysql://root:root@127.0.0.1:3306/libretv_metadata"

行为：
    1. 在目标库执行 sql/schema.sql（幂等建表）
    2. 逐表 TRUNCATE 目标表
    3. 按「目标表字段顺序 ∩ 源表字段」，分批把数据写进 MySQL
    4. 重置自增序列并 ANALYZE

注意：迁移期间请先停掉目标服务器上的元数据服务，避免采集任务同时写库。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from urllib.parse import unquote, urlparse

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import aiomysql  # noqa: E402
import asyncpg  # noqa: E402

TABLES = ("videos", "sync_state", "sync_runs", "sync_settings")
SCHEMA_FILE = BASE_DIR / "sql" / "schema.sql"


def mysql_parts(dsn: str) -> dict:
    parsed = urlparse(dsn)
    return {
        "host": parsed.hostname or "127.0.0.1",
        "port": parsed.port or 3306,
        "user": unquote(parsed.username or "root"),
        "password": unquote(parsed.password or ""),
        "db": unquote((parsed.path or "/").lstrip("/")),
    }


async def ensure_mysql_schema(conn: aiomysql.Connection, database: str) -> None:
    raw = SCHEMA_FILE.read_text(encoding="utf-8-sig")
    body = "\n".join(line for line in raw.splitlines() if not line.strip().startswith("--"))
    async with conn.cursor() as cur:
        await cur.execute(f"USE `{database}`")
        for statement in (part.strip() for part in body.split(";")):
            if not statement:
                continue
            try:
                await cur.execute(statement)
            except Exception as exc:  # noqa: BLE001 - 表已存在时允许跳过
                print(f"[schema] 跳过: {exc}")


async def mysql_columns(conn: aiomysql.Connection, database: str, table: str) -> list[tuple[str, str]]:
    async with conn.cursor() as cur:
        await cur.execute(
            "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s ORDER BY ORDINAL_POSITION",
            (database, table),
        )
        return [(row[0], row[1]) for row in await cur.fetchall()]


async def pg_columns(conn: asyncpg.Connection, table: str) -> list[str]:
    rows = await conn.fetch(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema = 'public' AND table_name = $1 ORDER BY ordinal_position",
        table,
    )
    return [row["column_name"] for row in rows]


def convert(value, mysql_type: str):
    if value is None:
        return None
    if mysql_type == "json":
        if isinstance(value, (list, dict)):
            return json.dumps(value, ensure_ascii=False)
        return value
    if mysql_type in {"tinyint", "smallint", "int", "bigint"} and isinstance(value, bool):
        return int(value)
    if mysql_type in {"datetime", "timestamp"} and isinstance(value, datetime):
        if value.tzinfo is not None:
            value = value.astimezone(timezone.utc).replace(tzinfo=None)
        return value
    if isinstance(value, Decimal):
        return value
    return value


async def copy_table(
    pg: asyncpg.Connection,
    my: aiomysql.Connection,
    database: str,
    table: str,
    batch: int,
) -> int:
    target_cols = await mysql_columns(my, database, table)
    if not target_cols:
        print(f"[{table}] 目标库没有该表，跳过")
        return 0
    source_cols = set(await pg_columns(pg, table))
    cols = [(name, dtype) for name, dtype in target_cols if name in source_cols]
    if not cols:
        print(f"[{table}] 没有可迁移的公共字段，跳过")
        return 0

    names = [name for name, _ in cols]
    types = [dtype for _, dtype in cols]
    quoted = ", ".join(f'"{name}"' for name in names)
    select_sql = f'SELECT {quoted} FROM "{table}"'
    insert_sql = (
        f"INSERT INTO `{table}` ({', '.join(f'`{n}`' for n in names)}) "
        f"VALUES ({', '.join(['%s'] * len(names))})"
    )

    async with my.cursor() as cur:
        await cur.execute(f"SET FOREIGN_KEY_CHECKS = 0")
        await cur.execute(f"TRUNCATE TABLE `{table}`")

    total = 0
    rows: list[tuple] = []
    async with my.cursor() as cur:
        for record in await pg.fetch(select_sql):
            rows.append(tuple(convert(record[name], dtype) for name, dtype in zip(names, types)))
            if len(rows) >= batch:
                await cur.executemany(insert_sql, rows)
                total += len(rows)
                rows.clear()
                print(f"[{table}] 已写入 {total} 行", end="\r", flush=True)
        if rows:
            await cur.executemany(insert_sql, rows)
            total += len(rows)
    await my.commit()

    async with my.cursor() as cur:
        await cur.execute(f"ANALYZE TABLE `{table}`")
        await cur.fetchall()
    print(f"[{table}] 完成，共 {total} 行" + " " * 10)
    return total


async def main() -> None:
    parser = argparse.ArgumentParser(description="PostgreSQL -> MySQL 数据迁移")
    parser.add_argument("--source", required=True, help="源库 PostgreSQL 连接串")
    parser.add_argument("--target", required=True, help="目标库 MySQL 连接串")
    parser.add_argument("--batch", type=int, default=2000, help="每批写入行数")
    parser.add_argument("--tables", nargs="*", default=list(TABLES), help="只迁移指定表")
    args = parser.parse_args()

    parts = mysql_parts(args.target)
    if not parts["db"]:
        raise SystemExit("目标连接串必须带库名，例如 mysql://root:root@127.0.0.1:3306/libretv_metadata")

    print(f"源库: {args.source.split('@')[-1]}")
    print(f"目标: {parts['host']}:{parts['port']}/{parts['db']}")

    pg = await asyncpg.connect(args.source)
    my = await aiomysql.connect(
        host=parts["host"], port=parts["port"], user=parts["user"],
        password=parts["password"], db=parts["db"], autocommit=False,
        charset="utf8mb4",
    )
    try:
        await ensure_mysql_schema(my, parts["db"])
        await my.commit()
        for table in args.tables:
            if table not in TABLES:
                print(f"[{table}] 不在迁移范围，跳过")
                continue
            await copy_table(pg, my, parts["db"], table, args.batch)
    finally:
        await pg.close()
        my.close()


if __name__ == "__main__":
    asyncio.run(main())
