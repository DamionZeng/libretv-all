from __future__ import annotations

import asyncio
import logging
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Query, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from app import db
from app.config import BASE_DIR, settings
from app.sync import full_pending, load_settings

log = logging.getLogger(__name__)
router = APIRouter()

TEMPLATE = Path(BASE_DIR) / "app" / "templates" / "admin.html"

CATEGORY_BUCKETS = {
    20: "电影",
    30: "电视剧",
    39: "动漫",
    45: "综艺",
    53: "体育",
    58: "短剧",
    0: "解说 / 花絮",
}


def _require_admin(request: Request, token: str | None) -> None:
    if not settings.admin_token:
        return
    if (token or request.query_params.get("token")) != settings.admin_token:
        raise HTTPException(status_code=401, detail="invalid admin token")


class SyncRequest(BaseModel):
    mode: str = Field(default="incremental", pattern="^(incremental|full|refresh)$")


class SchedulerRequest(BaseModel):
    enabled: bool
    interval_minutes: int = Field(default=1440, ge=5, le=10080)


@router.get("/admin", include_in_schema=False)
async def admin_page() -> HTMLResponse:
    return HTMLResponse(TEMPLATE.read_text(encoding="utf-8"))


@router.get("/admin/api/overview")
async def overview(request: Request, x_admin_token: str | None = Header(default=None)):
    _require_admin(request, x_admin_token)
    pool = await db.get_pool()
    async with pool.acquire() as conn:
        stats = await conn.fetchrow(
            "SELECT count(*) AS videos, "
            "SUM(pic_source IS NOT NULL) AS with_poster, "
            "SUM(poster_path IS NOT NULL) AS cached_posters, "
            "SUM(year IS NOT NULL) AS with_year, "
            "SUM(source_updated_at > NOW() - INTERVAL 24 HOUR) AS updated_24h, "
            "SUM(created_at > NOW() - INTERVAL 24 HOUR) AS added_24h "
            "FROM videos"
        )
        state = await conn.fetchrow(
            "SELECT active_domain, page_count, full_page, incremental_cursor, "
            "last_incremental_at, last_full_at FROM sync_state WHERE id = 1"
        )
        running = await conn.fetchrow(
            "SELECT id, mode, started_at, pages_done, last_page, items_upserted "
            "FROM sync_runs WHERE status = 'running' ORDER BY id DESC LIMIT 1"
        )
        last_run = await conn.fetchrow(
            "SELECT id, mode, status, started_at, finished_at, pages_done, "
            "items_fetched, items_upserted, items_failed, items_inserted, "
            "items_updated, error FROM sync_runs ORDER BY id DESC LIMIT 1"
        )
        runs = await conn.fetch(
            "SELECT id, mode, status, started_at, finished_at, pages_done, "
            "items_fetched, items_upserted, items_failed, items_inserted, "
            "items_updated, error FROM sync_runs ORDER BY id DESC LIMIT 12"
        )
        buckets = await conn.fetch(
            "SELECT type_id_1, count(*) AS n FROM videos GROUP BY type_id_1 ORDER BY n DESC"
        )
        top_types = await conn.fetch(
            "SELECT type_name, count(*) AS n FROM videos "
            "WHERE type_name IS NOT NULL AND type_name <> '' "
            "GROUP BY type_name ORDER BY n DESC LIMIT 10"
        )

    cfg = await load_settings()
    pending = await full_pending()
    done_pages = int(state["full_page"] or 0) if state else 0
    total_pages = int(state["page_count"] or 0) if state else 0

    next_run = None
    if cfg["scheduler_enabled"]:
        if pending:
            next_run = "回填中"
        elif state and state["last_incremental_at"]:
            next_run = (
                state["last_incremental_at"]
                + timedelta(minutes=int(cfg["interval_minutes"]))
            ).isoformat()
        else:
            next_run = "即将执行"

    return {
        "data": {
            "videos": int(stats["videos"]),
            "withPoster": int(stats["with_poster"]),
            "cachedPosters": int(stats["cached_posters"]),
            "withYear": int(stats["with_year"]),
            "updated24h": int(stats["updated_24h"]),
            "added24h": int(stats["added_24h"]),
        },
        "backfill": {
            "donePages": done_pages,
            "totalPages": total_pages,
            "percent": round(done_pages * 100.0 / total_pages, 2) if total_pages else 0,
            "finished": not pending,
        },
        "scheduler": {
            "enabled": bool(cfg["scheduler_enabled"]),
            "intervalMinutes": int(cfg["interval_minutes"]),
            "nextRunAt": next_run,
        },
        "state": dict(state) if state else None,
        "running": dict(running) if running else None,
        "lastRun": dict(last_run) if last_run else None,
        "runs": [dict(row) for row in runs],
        "buckets": [
            {
                "key": int(row["type_id_1"]) if row["type_id_1"] is not None else 0,
                "name": CATEGORY_BUCKETS.get(row["type_id_1"], "其他"),
                "count": int(row["n"]),
            }
            for row in buckets
        ],
        "topTypes": [
            {"name": row["type_name"], "count": int(row["n"])} for row in top_types
        ],
        "sourceDomains": list(settings.source_domains),
        "serverTime": datetime.now(timezone.utc).isoformat(),
    }


@router.get("/admin/api/runs")
async def runs(
    request: Request,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    x_admin_token: str | None = Header(default=None),
):
    _require_admin(request, x_admin_token)
    pool = await db.get_pool()
    async with pool.acquire() as conn:
        total = await conn.fetchval("SELECT count(*) FROM sync_runs")
        rows = await conn.fetch(
            "SELECT id, mode, status, started_at, finished_at, pages_done, "
            "items_fetched, items_upserted, items_failed, items_inserted, "
            "items_updated, error FROM sync_runs ORDER BY id DESC LIMIT %s OFFSET %s",
            limit, offset,
        )
    return {"total": int(total), "items": [dict(row) for row in rows]}


LOG_WINDOW_PAD = timedelta(seconds=5)
LOG_RETENTION_DAYS = 30
LOG_TS_RE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}),(\d{3}) ")


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _local(value: datetime) -> datetime:
    """把库里的 naive UTC 时间转成本地时区的 naive 时间。"""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone().replace(tzinfo=None)


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


def _parse_ts(line: str) -> datetime | None:
    match = LOG_TS_RE.match(line)
    if not match:
        return None
    try:
        return datetime.strptime(f"{match.group(1)},{match.group(2)}", "%Y-%m-%d %H:%M:%S,%f")
    except ValueError:
        return None


def _candidate_files(start: datetime, end: datetime) -> list[Path]:
    """按采集时间窗口找出可能包含日志的文件（含按天轮转的历史文件）。"""
    files: list[Path] = []
    day = start.date()
    while day <= end.date():
        rotated = settings.log_file.with_name(f"{settings.log_file.name}.{day.isoformat()}")
        if rotated.exists():
            files.append(rotated)
        day += timedelta(days=1)
    if settings.log_file.exists():
        files.append(settings.log_file)
    return files


def _read_lines(path: Path) -> list[str]:
    try:
        return path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []


@router.get("/admin/api/logs")
async def service_logs(
    request: Request,
    lines: int = Query(500, ge=20, le=2000),
    level: str = Query("all", pattern="^(all|info|warning|error)$"),
    q: str = Query("", max_length=80),
    run_id: int | None = Query(None, ge=1),
    x_admin_token: str | None = Header(default=None),
):
    _require_admin(request, x_admin_token)

    run: dict | None = None
    start: datetime | None = None
    end: datetime | None = None
    if run_id is not None:
        pool = await db.get_pool()
        row = await pool.fetchrow(
            "SELECT id, mode, status, started_at, finished_at FROM sync_runs WHERE id = %s",
            run_id,
        )
        if row is None:
            raise HTTPException(status_code=404, detail="run not found")
        run = dict(row)
        # MySQL DATETIME 存的是 UTC 的 naive 时间，按 UTC 解释后再转本地时区
        start = _local(row["started_at"]) - LOG_WINDOW_PAD
        finished = row["finished_at"] or _utcnow()
        end = _local(finished) + LOG_WINDOW_PAD
        files = _candidate_files(start, end)
    else:
        files = [settings.log_file] if settings.log_file.exists() else []

    if not files:
        return {
            "file": str(settings.log_file),
            "exists": False,
            "run": run,
            "window": None,
            "lines": [],
            "total": 0,
            "retentionDays": LOG_RETENTION_DAYS,
            "updatedAt": datetime.now(timezone.utc).isoformat(),
        }

    keyword = q.strip().lower()
    matched: list[str] = []
    for path in files:
        for line in await asyncio.to_thread(_read_lines, path):
            if start is not None:
                ts = _parse_ts(line)
                if ts is None or ts < start or ts > end:
                    continue
            if level != "all" and f" {level.upper()} " not in line.upper():
                continue
            if keyword and keyword not in line.lower():
                continue
            matched.append(line)

    finished_at = run["finished_at"] if run else None
    expired = bool(
        run and finished_at
        and _utcnow() - _aware(finished_at) > timedelta(days=LOG_RETENTION_DAYS)
    )

    return {
        "file": str(settings.log_file),
        "exists": True,
        "run": run,
        "window": (
            {"from": start.isoformat(), "to": end.isoformat()}
            if start is not None and end is not None else None
        ),
        "lines": matched[-lines:],
        "total": len(matched),
        "expired": expired,
        "retentionDays": LOG_RETENTION_DAYS,
        "updatedAt": datetime.now(timezone.utc).isoformat(),
    }


@router.get("/admin/api/videos")
async def browse_videos(
    request: Request,
    q: str = Query("", max_length=80),
    category: str = Query("all"),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    x_admin_token: str | None = Header(default=None),
):
    _require_admin(request, x_admin_token)
    clauses: list[str] = []
    params: list = []
    if q.strip():
        params.append(f"%{q.strip()}%")
        clauses.append("name LIKE %s")
    if category != "all" and category.isdigit():
        params.append(int(category))
        clauses.append("type_id_1 = %s")
    where = " AND ".join(clauses) if clauses else "1 = 1"

    pool = await db.get_pool()
    async with pool.acquire() as conn:
        total = await conn.fetchval(f"SELECT count(*) FROM videos WHERE {where}", *params)
        rows = await conn.fetch(
            "SELECT id, name, type_name, year, area, score, score_num, hits, "
            "pic_source, poster_path, source_updated_at, created_at "
            f"FROM videos WHERE {where} ORDER BY source_updated_at DESC, id DESC "
            "LIMIT %s OFFSET %s",
            *params, page_size, (page - 1) * page_size,
        )
    base = settings.public_base_url or str(request.base_url).rstrip("/")
    items = []
    for row in rows:
        item = dict(row)
        item["id"] = str(item["id"])
        item["poster"] = f"{base}/media/posters/{row['id']}" if row["pic_source"] else ""
        items.append(item)
    return {
        "total": int(total),
        "page": page,
        "page_size": page_size,
        "pages": max(1, (int(total) + page_size - 1) // page_size),
        "items": items,
    }


@router.post("/admin/api/sync")
async def trigger_sync(
    request: Request,
    body: SyncRequest,
    x_admin_token: str | None = Header(default=None),
):
    _require_admin(request, x_admin_token)
    pool = await db.get_pool()
    await pool.execute(
        "UPDATE sync_settings SET requested_mode = %s, requested_at = NOW(), "
        "updated_at = NOW() WHERE id = 1",
        body.mode,
    )
    return {"status": "queued", "mode": body.mode}


@router.post("/admin/api/scheduler")
async def update_scheduler(
    request: Request,
    body: SchedulerRequest,
    x_admin_token: str | None = Header(default=None),
):
    _require_admin(request, x_admin_token)
    pool = await db.get_pool()
    await pool.execute(
        "UPDATE sync_settings SET scheduler_enabled = %s, interval_minutes = %s, "
        "updated_at = NOW() WHERE id = 1",
        body.enabled, body.interval_minutes,
    )
    return {"status": "ok", **body.model_dump()}
