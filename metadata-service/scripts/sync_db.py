#!/usr/bin/env python3
"""通过源库 / 目标库连接串直接同步表结构与数据（不生成备份文件）。

    python scripts/sync_db.py \
        --source "postgresql://postgres:postgres@127.0.0.1:5432/libretv_metadata" \
        --target "postgresql://libretv:密码@服务器IP:5432/libretv_metadata"

做的事：
1. 在目标库执行 sql/schema.sql（幂等 DDL，缺表/缺列会自动补齐）
2. 逐表 TRUNCATE 目标表，再从源库分批读取、COPY 写入目标库
3. 重设自增序列并 ANALYZE

注意：同步期间目标库会被清空重写，请先停掉目标服务器上的元数据服务。
若服务器未开放 5432，可先建 SSH 隧道：
    ssh -N -L 15432:127.0.0.1:5432 root@服务器IP
然后 --target 用 127.0.0.1:15432。
"""
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

import asyncpg

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TABLES = ("videos", "sync_state", "sync_runs", "sync_settings")


async def apply_schema(target: asyncpg.Connection) -> None:
    """逐条执行 schema.sql，单条失败（如 pg_trgm 权限）不影响其余语句。"""
    sql = (ROOT / "sql" / "schema.sql").read_text(encoding="utf-8")
    for statement in (part.strip() for part in sql.split(";")):
        if not statement:
            continue
        try:
            await target.execute(statement)
        except asyncpg.PostgresError as exc:
            first_line = statement.splitlines()[0]
            print(f"[schema] 跳过 {first_line[:60]} → {exc}")


async def columns_of(conn: asyncpg.Connection, table: str) -> list[str]:
    rows = await conn.fetch(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = $1
        ORDER BY ordinal_position
        """,
        table,
    )
    return [row["column_name"] for row in rows]


async def sync_table(
    source: asyncpg.Connection,
    target: asyncpg.Connection,
    table: str,
    batch: int,
) -> int:
    source_columns = await columns_of(source, table)
    target_columns = set(await columns_of(target, table))
    if not target_columns:
        raise SystemExit(f"目标库缺少表 {table}，请先同步表结构")
    # 只同步两边都存在的列，兼容版本差异
    columns = [name for name in source_columns if name in target_columns]
    if not columns:
        raise SystemExit(f"表 {table} 没有可同步的公共列")

    column_sql = ", ".join(f'"{name}"' for name in columns)
    copied = 0
    async with target.transaction():
        await target.execute(f'TRUNCATE TABLE "{table}"')
        pending: list[tuple] = []
        async with source.transaction():
            async for record in source.cursor(
                f'SELECT {column_sql} FROM "{table}"', prefetch=batch
            ):
                pending.append(tuple(record))
                if len(pending) >= batch:
                    await target.copy_records_to_table(
                        table, records=pending, columns=columns
                    )
                    copied += len(pending)
                    pending.clear()
                    print(f"\r[sync] {table}: {copied} 行", end="", flush=True)
        if pending:
            await target.copy_records_to_table(table, records=pending, columns=columns)
            copied += len(pending)
    print(f"\r[sync] {table}: {copied} 行")
    return copied


async def reset_sequences(target: asyncpg.Connection) -> None:
    rows = await target.fetch(
        """
        SELECT table_name, column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND column_default LIKE 'nextval%'
        """
    )
    for row in rows:
        await target.execute(
            f'SELECT setval(pg_get_serial_sequence($1, $2), '
            f'COALESCE((SELECT max("{row["column_name"]}") FROM "{row["table_name"]}"), 0) + 1, false)',
            row["table_name"],
            row["column_name"],
        )


async def main_async(args: argparse.Namespace) -> None:
    print("[sync] 连接源库")
    source = await asyncpg.connect(args.source)
    print("[sync] 连接目标库")
    target = await asyncpg.connect(args.target)
    try:
        if not args.skip_schema:
            print("[sync] 同步表结构")
            await apply_schema(target)

        for table in args.tables:
            await sync_table(source, target, table, args.batch)

        await reset_sequences(target)
        await target.execute("ANALYZE")
        print("[sync] 完成")
    finally:
        await source.close()
        await target.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="源库 → 目标库直接同步（结构 + 数据）")
    parser.add_argument("--source", required=True, help="源库 DSN")
    parser.add_argument("--target", required=True, help="目标库 DSN")
    parser.add_argument(
        "--tables",
        nargs="+",
        default=list(DEFAULT_TABLES),
        help=f"要同步的表，默认 {' '.join(DEFAULT_TABLES)}",
    )
    parser.add_argument("--batch", type=int, default=2000, help="每批行数，默认 2000")
    parser.add_argument("--skip-schema", action="store_true", help="只同步数据，不执行建表语句")
    args = parser.parse_args()

    try:
        asyncio.run(main_async(args))
    except asyncpg.PostgresError as exc:
        raise SystemExit(f"数据库操作失败: {exc}") from exc
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
