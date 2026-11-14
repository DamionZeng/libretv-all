from __future__ import annotations

import argparse
import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Any

from app import db
from app.config import settings
from app.ingest import UPSERT_SQL, normalize, replace_poster
from app.source import SourceClient, SourceError

log = logging.getLogger(__name__)

SYNC_LOCK_ID = 947_311_001
_process_lock = asyncio.Lock()


async def _prepare_rows(source: SourceClient, payload: dict) -> tuple[list[tuple], int]:
    rows: list[tuple] = []
    failed = 0
    for item in payload.get("list") or []:
        try:
            rows.append(normalize(item, source.domain))
        except Exception as exc:  # 单条脏数据不影响整页
            failed += 1
            log.debug("skip invalid item: %s", exc)

    missing = [(index, row) for index, row in enumerate(rows) if row[23] is None]
    if missing:
        semaphore = asyncio.Semaphore(4)

        async def fill(index: int, row: tuple) -> None:
            async with semaphore:
                poster = await source.fetch_detail_poster(row[0])
            if poster:
                rows[index] = replace_poster(row, poster)

        await asyncio.gather(*(fill(index, row) for index, row in missing))
    return rows, failed


async def _upsert(pool, rows: list[tuple]) -> None:
    if not rows:
        return
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.executemany(UPSERT_SQL, rows)


async def run_sync(mode: str = "incremental", pages: int | None = None) -> dict[str, Any]:
    """执行一次采集。full 模式支持断点续采，incremental 只抓最新页，auto 自动二选一。"""
    mode = mode if mode in {"incremental", "full", "refresh", "auto"} else "incremental"
    async with _process_lock:
        pool = await db.init_pool()
        await db.ensure_schema(pool)
        if mode == "auto":
            mode = "full" if await full_pending() else "incremental"
        lock_conn = await pool.acquire()
        source: SourceClient | None = None
        run_id: int | None = None
        stats = {
            "mode": mode,
            "status": "running",
            "pagesDone": 0,
            "itemsFetched": 0,
            "itemsUpserted": 0,
            "itemsFailed": 0,
            "lastPage": None,
        }
        try:
            locked = await lock_conn.fetchval("SELECT pg_try_advisory_lock($1)", SYNC_LOCK_ID)
            if not locked:
                return {"mode": mode, "status": "skipped", "reason": "another sync is running"}

            source = SourceClient()
            run_row = await pool.fetchrow(
                "INSERT INTO sync_runs (mode) VALUES ($1) RETURNING id, started_at", mode
            )
            run_id = run_row["id"]
            run_started = run_row["started_at"]

            first = await source.fetch_page(1)
            page_count = int(first.get("pagecount") or 0)
            if page_count <= 0:
                raise SourceError("source returned empty page count")
            await pool.execute(
                "UPDATE sync_state SET page_count = $1, active_domain = $2, "
                "updated_at = now() WHERE id = 1",
                page_count, source.domain,
            )

            if mode == "full":
                state = await pool.fetchrow("SELECT full_page FROM sync_state WHERE id = 1")
                start = int(state["full_page"] if state else 0) + 1
                if start > page_count:
                    log.info("full backfill already complete (%s pages)", page_count)
                    start = page_count
                end = page_count if pages is None else min(page_count, start + pages - 1)
            else:
                max_pages = settings.incremental_max_pages if mode == "incremental" else page_count
                end = min(page_count, pages or max_pages)
                start = 1

            cursor = None
            if mode == "incremental":
                # 用上次实际覆盖到的时间点做边界，避免一次没追平时漏数据
                cursor = await pool.fetchval(
                    "SELECT incremental_cursor FROM sync_state WHERE id = 1"
                )
                if cursor is None:
                    cursor = await pool.fetchval("SELECT max(source_updated_at) FROM videos")

            log.info("sync start mode=%s pages=%s..%s (total pages=%s)",
                     mode, start, end, page_count)

            newest_in_page: datetime | None = None
            last_page_oldest: datetime | None = None
            caught_up = False
            for page in range(start, end + 1):
                try:
                    payload = first if page == 1 else await source.fetch_page(page)
                except SourceError as exc:
                    log.error("page %s failed: %s", page, exc)
                    stats["status"] = "partial"
                    break

                rows, failed = await _prepare_rows(source, payload)
                await _upsert(pool, rows)

                stats["pagesDone"] += 1
                stats["itemsFetched"] += len(payload.get("list") or [])
                stats["itemsUpserted"] += len(rows)
                stats["itemsFailed"] += failed
                stats["lastPage"] = page

                timestamps = [row[34] for row in rows if row[34] is not None]
                if timestamps:
                    newest_in_page = max(timestamps)
                    last_page_oldest = min(timestamps)

                if mode == "full":
                    await pool.execute(
                        "UPDATE sync_state SET full_page = $1, active_domain = $2, "
                        "updated_at = now() WHERE id = 1",
                        page, source.domain,
                    )

                if page % 10 == 0 or page == end:
                    log.info("page %s/%s: fetched=%s upserted=%s failed=%s",
                             page, end, stats["itemsFetched"],
                             stats["itemsUpserted"], stats["itemsFailed"])

                if mode == "incremental" and cursor and newest_in_page and newest_in_page <= cursor:
                    log.info("no newer records after page %s, stop incremental", page)
                    caught_up = True
                    break

            if mode == "incremental":
                next_cursor = await pool.fetchval("SELECT max(source_updated_at) FROM videos")
                if cursor is not None and not caught_up and last_page_oldest is not None:
                    # 到页数上限或中途失败仍未追平：游标只推进到本轮已覆盖的最旧时间，下一轮从这里继续
                    next_cursor = last_page_oldest
                await pool.execute(
                    "UPDATE sync_state SET active_domain = $1, incremental_cursor = $2, "
                    "last_incremental_at = now(), updated_at = now() WHERE id = 1",
                    source.domain, next_cursor,
                )
            elif mode == "full" and stats["lastPage"] and stats["lastPage"] >= page_count:
                await pool.execute(
                    "UPDATE sync_state SET last_full_at = now(), updated_at = now() WHERE id = 1"
                )

            stats["status"] = stats["status"] if stats["status"] != "running" else "success"
            # 区分真正的新增与对已有条目的覆盖更新，便于核对采集效果
            counts = await pool.fetchrow(
                "SELECT count(*) FILTER (WHERE created_at >= $1) AS inserted, "
                "count(*) FILTER (WHERE created_at < $1 AND updated_at >= $1) AS updated "
                "FROM videos WHERE updated_at >= $1",
                run_started,
            )
            stats["itemsInserted"] = int(counts["inserted"] or 0)
            stats["itemsUpdated"] = int(counts["updated"] or 0)
            await pool.execute(
                "UPDATE sync_runs SET status=$1, finished_at=now(), pages_done=$2, "
                "items_fetched=$3, items_upserted=$4, items_failed=$5, last_page=$6, "
                "items_inserted=$8, items_updated=$9 WHERE id=$7",
                stats["status"], stats["pagesDone"], stats["itemsFetched"],
                stats["itemsUpserted"], stats["itemsFailed"], stats["lastPage"], run_id,
                stats["itemsInserted"], stats["itemsUpdated"],
            )
            return stats
        except Exception as exc:
            log.exception("sync failed")
            if run_id is not None:
                await pool.execute(
                    "UPDATE sync_runs SET status='failed', finished_at=now(), error=$1 "
                    "WHERE id=$2",
                    str(exc)[:2000], run_id,
                )
            raise
        finally:
            if source is not None:
                await source.close()
            try:
                await lock_conn.fetchval("SELECT pg_advisory_unlock($1)", SYNC_LOCK_ID)
            finally:
                await pool.release(lock_conn)


async def full_pending() -> bool:
    """全量回填是否还没跑完（新增加的源分页也会触发续采）。"""
    pool = await db.get_pool()
    row = await pool.fetchrow("SELECT full_page, page_count FROM sync_state WHERE id = 1")
    if row is None:
        return True
    page_count = int(row["page_count"] or 0)
    return page_count <= 0 or int(row["full_page"] or 0) < page_count


async def mark_stale_runs() -> None:
    """服务重启后把上次残留的 running 记录收尾（持有采集锁时才安全）。"""
    pool = await db.get_pool()
    conn = await pool.acquire()
    locked = False
    try:
        locked = await conn.fetchval("SELECT pg_try_advisory_lock($1)", SYNC_LOCK_ID)
        if not locked:
            return
        await pool.execute(
            "UPDATE sync_runs SET status='interrupted', finished_at=now(), "
            "error='service restarted' WHERE status='running'"
        )
    finally:
        if locked:
            await conn.fetchval("SELECT pg_advisory_unlock($1)", SYNC_LOCK_ID)
        await pool.release(conn)


async def load_settings() -> dict[str, Any]:
    """读取调度配置，缺失时用 .env 的默认值。"""
    pool = await db.get_pool()
    row = await pool.fetchrow(
        "SELECT scheduler_enabled, interval_minutes FROM sync_settings WHERE id = 1"
    )
    if row is None:
        return {"scheduler_enabled": True,
                "interval_minutes": settings.sync_interval_minutes}
    return dict(row)


async def claim_request() -> str | None:
    """原子取走后台手动触发的采集请求，确保同一请求只执行一次。"""
    pool = await db.get_pool()
    return await pool.fetchval(
        "WITH claimed AS ("
        " SELECT requested_mode FROM sync_settings WHERE id = 1 FOR UPDATE"
        ") UPDATE sync_settings SET requested_mode = NULL, requested_at = NULL, "
        "updated_at = now() FROM claimed "
        "WHERE sync_settings.id = 1 AND claimed.requested_mode IS NOT NULL "
        "RETURNING claimed.requested_mode"
    )


async def schedule_due(interval_minutes: int) -> bool:
    pool = await db.get_pool()
    last = await pool.fetchval("SELECT last_incremental_at FROM sync_state WHERE id = 1")
    if last is None:
        return True
    return (datetime.now(timezone.utc) - last).total_seconds() >= interval_minutes * 60


async def daemon(interval_minutes: int | None = None) -> None:
    """单实例调度器：轮询 sync_settings，支持在线启停、调整频率和手动触发。"""
    await db.get_pool()
    if interval_minutes:
        pool = await db.get_pool()
        await pool.execute(
            "UPDATE sync_settings SET interval_minutes = $1, updated_at = now() WHERE id = 1",
            interval_minutes,
        )
    log.info("sync daemon started")
    while True:
        delay = 5
        try:
            mode = await claim_request()
            if mode is None:
                cfg = await load_settings()
                if cfg["scheduler_enabled"]:
                    if await full_pending():
                        mode = "full"
                        delay = 5
                    elif await schedule_due(int(cfg["interval_minutes"])):
                        mode = "incremental"
            if mode:
                summary = await run_sync(mode)
                log.info("scheduled sync (%s): %s", mode, summary)
                delay = 2
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("scheduled sync failed")
            delay = 30
        await asyncio.sleep(delay)


def main() -> None:
    parser = argparse.ArgumentParser(description="暴风资源元数据采集器")
    parser.add_argument("--mode", choices=["incremental", "full", "refresh", "auto"],
                        default="incremental", help="采集模式；auto=未回填完则续采，否则增量")
    parser.add_argument("--pages", type=int, default=None, help="本次最多采集的页数")
    parser.add_argument("--daemon", action="store_true", help="常驻定时增量采集")
    parser.add_argument("--interval", type=int, default=None, help="常驻模式间隔分钟")
    parser.add_argument("--init-db", action="store_true", help="只初始化数据库")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    async def runner() -> None:
        if args.init_db:
            pool = await db.init_pool()
            await db.ensure_schema(pool)
            await db.close_pool()
            print(json.dumps({"status": "ok", "message": "schema ready"}, ensure_ascii=False))
            return
        if args.daemon:
            try:
                await daemon(args.interval)
            finally:
                await db.close_pool()
            return
        try:
            summary = await run_sync(args.mode, args.pages)
            print(json.dumps(summary, ensure_ascii=False, default=str))
        finally:
            await db.close_pool()

    try:
        asyncio.run(runner())
    except KeyboardInterrupt:
        log.info("stopped")


if __name__ == "__main__":
    main()
