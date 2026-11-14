from __future__ import annotations

import json
import logging
import re

from fastapi import APIRouter, Header, HTTPException, Query, Request

from app import db
from app.config import settings

log = logging.getLogger(__name__)
router = APIRouter()

BASE_FILTER = (
    "name <> '' AND pic_source IS NOT NULL "
    "AND year IS NOT NULL AND year BETWEEN 1900 AND 2100 "
    "AND (type_id IS NULL OR type_id <> 29)"
)
MOVIE_BASE = "type_id_1 = 20"
TV_BASE = "type_id_1 = 30"
ALL_BASE = "type_id_1 IN (20, 30, 39, 45, 58)"

# MySQL 中 DESC 排序 NULL 自动排在最后，无需 NULLS LAST
ORDERING = {
    "S": "score DESC, score_num DESC, hits DESC, id DESC",
    "R": "source_updated_at DESC, id DESC",
    "T": "source_updated_at DESC, id DESC",
    "U": "hits DESC, source_updated_at DESC, id DESC",
}

CARD_COLUMNS = """
    id, name, year, area, type_name, genres, score, score_num, hits, remarks, pic_source
"""


def _base_url(request: Request) -> str:
    return settings.public_base_url or str(request.base_url).rstrip("/")


def _poster_url(request: Request, video_id: int) -> str:
    return f"{_base_url(request)}/media/posters/{video_id}"


def _genres(value) -> list[str]:
    """MySQL JSON 列读出的是字符串，PG 数组则是列表，这里统一成 list。"""
    if value is None:
        return []
    if isinstance(value, list):
        return [str(v) for v in value]
    try:
        parsed = json.loads(value)
    except (TypeError, ValueError):
        return []
    return [str(v) for v in parsed] if isinstance(parsed, list) else []


LOOKUP_COLUMNS = """
    id, name, sub_name, year, area, lang, type_name, genres, score, score_num,
    remarks, actor, director, writer, blurb, episode_count, is_end, hits
"""


@router.get("/api/v1/lookup")
async def lookup_video(
    request: Request,
    title: str = Query(..., min_length=1, max_length=120),
    year: int | None = Query(None, ge=1900, le=2100),
):
    """按片名反查本地元数据，供 App 详情页补充简介、演职员、评分等信息。"""
    text = title.strip()
    pool = await db.get_pool()

    sql = f"SELECT {LOOKUP_COLUMNS} FROM videos WHERE (name = %s OR sub_name = %s)"
    params: list = [text, text]
    if year is not None:
        sql += " AND year = %s"
        params.append(year)
    sql += " ORDER BY (name = %s) DESC, score_num DESC, hits DESC LIMIT 1"
    params.append(text)
    row = await pool.fetchrow(sql, *params)

    if row is None:
        like = f"%{text}%"
        sql = (
            f"SELECT {LOOKUP_COLUMNS} FROM videos "
            "WHERE name LIKE %s OR sub_name LIKE %s"
        )
        params = [like, like]
        if year is not None:
            sql += " AND year = %s"
            params.append(year)
        sql += " ORDER BY score_num DESC, hits DESC LIMIT 1"
        row = await pool.fetchrow(sql, *params)

    if row is None:
        # 片源标题带「粤语 / 国语 / HD」等后缀时，反向用库内片名做包含匹配
        sql = (
            f"SELECT {LOOKUP_COLUMNS} FROM videos "
            "WHERE %s LIKE CONCAT('%%', name, '%%') AND CHAR_LENGTH(name) >= 2"
        )
        params = [text]
        if year is not None:
            sql += " AND year = %s"
            params.append(year)
        sql += " ORDER BY CHAR_LENGTH(name) DESC, score_num DESC LIMIT 1"
        row = await pool.fetchrow(sql, *params)

    if row is None:
        raise HTTPException(status_code=404, detail="video not found")
    data = dict(row)
    data["genres"] = _genres(data.get("genres"))
    data["pic"] = _poster_url(request, int(row["id"]))
    return data


def _parse_tags(tags: str | None) -> tuple[int | None, list[str]]:
    year: int | None = None
    labels: list[str] = []
    for part in re.split(r"[,，]", tags or ""):
        part = part.strip()
        if not part:
            continue
        if year is None and re.fullmatch(r"(19|20)\d{2}", part):
            year = int(part)
        else:
            labels.append(part)
    return year, labels


def _where(media_type: str | None, labels: list[str], year: int | None) -> tuple[str, list]:
    clauses = [BASE_FILTER]
    params: list = []
    label = labels[0] if labels else None

    if label == "综艺":
        clauses.append("type_id_1 = 45")
    elif label == "动画":
        clauses.append("type_id_1 = 39")
    elif label == "纪录片":
        clauses.append("(type_id = 28 OR JSON_CONTAINS(genres, %s))")
        params.append(json.dumps("纪录片", ensure_ascii=False))
    elif label == "短片":
        clauses.append("type_id_1 = 58")
    elif label:
        params.append(json.dumps(label, ensure_ascii=False))
        params.append(f"%{label}%")
        clauses.append("(JSON_CONTAINS(genres, %s) OR type_name LIKE %s)")
    elif media_type == "movie":
        clauses.append(MOVIE_BASE)
    elif media_type == "tv":
        clauses.append(TV_BASE)
    else:
        clauses.append(ALL_BASE)

    if year is not None:
        params.append(year)
        clauses.append("year = %s")

    return " AND ".join(clauses), params


async def _query_catalog(
    *,
    media_type: str | None,
    tags: str | None,
    start: int,
    count: int,
    sort: str,
    min_score: float = 0.0,
) -> tuple[int, list]:
    year, labels = _parse_tags(tags)
    where, params = _where(media_type, labels, year)
    if min_score > 0:
        params.append(min_score)
        where += " AND score >= %s"
    order = ORDERING.get((sort or "U").upper(), ORDERING["U"])
    start = max(0, start)
    count = max(1, min(count, 100))

    pool = await db.get_pool()
    async with pool.acquire() as conn:
        total = await conn.fetchval(f"SELECT count(*) FROM videos WHERE {where}", *params)
        rows = await conn.fetch(
            f"SELECT {CARD_COLUMNS} FROM videos WHERE {where} "
            f"ORDER BY {order} LIMIT %s OFFSET %s",
            *params, count, start,
        )
    return int(total), rows


def _compat_item(request: Request, row) -> dict:
    score = float(row["score"]) if row["score"] is not None else 0.0
    genres = _genres(row["genres"])
    poster = _poster_url(request, int(row["id"]))
    subtitle = f"{row['year']} / {row['area'] or ''} / {' '.join(genres)}"
    return {
        "id": str(row["id"]),
        "title": row["name"],
        "pic": {"normal": poster, "large": poster},
        "rating": {
            "value": f"{score:.1f}" if score > 0 else "",
            "count": int(row["score_num"] or 0),
        },
        "card_subtitle": subtitle,
        "year": str(row["year"]),
        "genres": genres,
        "type_name": row["type_name"],
        "remarks": row["remarks"] or "",
        "source": "bfzy",
    }


@router.get("/health")
async def health(request: Request):
    pool = await db.get_pool()
    async with pool.acquire() as conn:
        total = await conn.fetchval("SELECT count(*) FROM videos")
        last_run = await conn.fetchrow(
            "SELECT mode, status, started_at, finished_at, items_upserted, error "
            "FROM sync_runs ORDER BY id DESC LIMIT 1"
        )
        state = await conn.fetchrow(
            "SELECT full_page, page_count, incremental_cursor, "
            "last_incremental_at, last_full_at FROM sync_state WHERE id = 1"
        )
    backfill = None
    if state:
        done = int(state["full_page"] or 0)
        total_pages = int(state["page_count"] or 0)
        backfill = {
            "donePages": done,
            "totalPages": total_pages or None,
            "percent": round(done * 100.0 / total_pages, 2) if total_pages else None,
            "finished": bool(total_pages and done >= total_pages),
            "lastFullAt": state["last_full_at"],
            "lastIncrementalAt": state["last_incremental_at"],
        }
    return {
        "status": "ok",
        "videos": int(total),
        "sourceDomains": list(settings.source_domains),
        "backfill": backfill,
        "lastSync": dict(last_run) if last_run else None,
        "baseUrl": _base_url(request),
    }


# ---------------------------------------------------------------------------
# 豆瓣 rexxar 兼容层：App 只需替换域名即可复用现有解析逻辑
# ---------------------------------------------------------------------------


@router.get("/rexxar/api/v2/{media_type}/recommend")
async def douban_compatible(
    media_type: str,
    request: Request,
    start: int = Query(0, ge=0),
    count: int = Query(20, ge=1, le=100),
    tags: str = "",
    sort: str = "U",
    min_score: float = Query(0, ge=0, le=10),
):
    if media_type not in {"movie", "tv"}:
        raise HTTPException(status_code=404, detail="unknown media type")
    total, rows = await _query_catalog(
        media_type=media_type, tags=tags, start=start, count=count, sort=sort,
        min_score=min_score,
    )
    items = [_compat_item(request, row) for row in rows]
    return {
        "total": total,
        "start": start,
        "count": len(items),
        "items": items,
        "source": "bfzy",
    }


@router.get("/api/v2/{media_type}/recommend", include_in_schema=False)
async def douban_compatible_alias(
    media_type: str,
    request: Request,
    start: int = Query(0, ge=0),
    count: int = Query(20, ge=1, le=100),
    tags: str = "",
    sort: str = "U",
):
    return await douban_compatible(media_type, request, start, count, tags, sort)


# ---------------------------------------------------------------------------
# 原生接口
# ---------------------------------------------------------------------------


CATEGORY_MAP = {
    "全部": (None, []),
    "电影": ("movie", []),
    "电视剧": ("tv", []),
    "综艺": ("tv", ["综艺"]),
    "动漫": ("tv", ["动画"]),
    "纪录片": ("tv", ["纪录片"]),
    "短剧": ("movie", ["短片"]),
}


@router.get("/api/v1/videos")
async def list_videos(
    request: Request,
    category: str = Query("全部"),
    year: str = Query("全部"),
    sort: str = Query("time", pattern="^(time|score|hot)$"),
    page: int = Query(1, ge=1),
    page_size: int = Query(28, ge=1, le=100),
):
    if category not in CATEGORY_MAP:
        raise HTTPException(status_code=400, detail="unknown category")
    media_type, labels = CATEGORY_MAP[category]
    year_value = int(year) if re.fullmatch(r"(19|20)\d{2}", year) else None
    where, params = _where(media_type, labels, year_value)
    order = {
        "time": ORDERING["R"],
        "score": ORDERING["S"],
        "hot": "hits DESC, source_updated_at DESC",
    }[sort]

    pool = await db.get_pool()
    async with pool.acquire() as conn:
        total = await conn.fetchval(f"SELECT count(*) FROM videos WHERE {where}", *params)
        rows = await conn.fetch(
            f"SELECT id, name, sub_name, year, area, lang, type_name, genres, score, "
            f"score_num, remarks, actor, director, hits, is_end, episode_count, "
            f"source_updated_at, pic_source FROM videos WHERE {where} "
            f"ORDER BY {order} LIMIT %s OFFSET %s",
            *params, page_size, (page - 1) * page_size,
        )

    items = []
    for row in rows:
        item = _compat_item(request, row)
        item.update({
            "sub_name": row["sub_name"],
            "lang": row["lang"],
            "actor": row["actor"],
            "director": row["director"],
            "hits": row["hits"],
            "is_end": bool(row["is_end"]),
            "episode_count": row["episode_count"],
            "updated_at": row["source_updated_at"],
        })
        items.append(item)

    return {
        "total": int(total),
        "page": page,
        "page_size": page_size,
        "pages": max(1, (int(total) + page_size - 1) // page_size),
        "items": items,
    }


@router.get("/api/v1/videos/{video_id}")
async def video_detail(video_id: int):
    pool = await db.get_pool()
    row = await pool.fetchrow("SELECT * FROM videos WHERE id = %s", video_id)
    if row is None:
        raise HTTPException(status_code=404, detail="video not found")
    payload = dict(row)
    payload["id"] = str(payload["id"])
    payload["genres"] = _genres(payload.get("genres"))
    payload["is_end"] = bool(payload.get("is_end"))
    if payload.get("douban_id") is not None:
        payload["douban_id"] = str(payload["douban_id"])
    return payload


@router.get("/api/v1/rankings")
async def rankings(
    request: Request,
    category: str = Query("电影"),
    limit: int = Query(20, ge=1, le=100),
):
    if category not in CATEGORY_MAP:
        raise HTTPException(status_code=400, detail="unknown category")
    media_type, labels = CATEGORY_MAP[category]
    where, params = _where(media_type, labels, None)

    pool = await db.get_pool()
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"SELECT {CARD_COLUMNS} FROM videos WHERE {where} "
            f"ORDER BY {ORDERING['S']} LIMIT %s",
            *params, limit,
        )
    return {"items": [_compat_item(request, row) for row in rows]}


@router.get("/api/v1/search")
async def search(
    request: Request,
    q: str = Query(..., min_length=1, max_length=80),
    page: int = Query(1, ge=1),
    page_size: int = Query(28, ge=1, le=100),
):
    pattern = f"%{q.strip()}%"
    where = f"{BASE_FILTER} AND (name LIKE %s OR sub_name LIKE %s)"
    params = [pattern, pattern]
    pool = await db.get_pool()
    async with pool.acquire() as conn:
        total = await conn.fetchval(f"SELECT count(*) FROM videos WHERE {where}", *params)
        rows = await conn.fetch(
            f"SELECT {CARD_COLUMNS} FROM videos WHERE {where} "
            f"ORDER BY {ORDERING['S']} LIMIT %s OFFSET %s",
            *params, page_size, (page - 1) * page_size,
        )
    return {
        "total": int(total),
        "page": page,
        "page_size": page_size,
        "items": [_compat_item(request, row) for row in rows],
    }


# ---------------------------------------------------------------------------
# 运维接口
# ---------------------------------------------------------------------------


def _require_admin(token: str | None) -> None:
    if settings.admin_token and token != settings.admin_token:
        raise HTTPException(status_code=401, detail="invalid admin token")


@router.get("/admin/status")
async def admin_status(request: Request, x_admin_token: str | None = Header(default=None)):
    _require_admin(x_admin_token)
    pool = await db.get_pool()
    async with pool.acquire() as conn:
        total = await conn.fetchval("SELECT count(*) FROM videos")
        with_poster = await conn.fetchval("SELECT count(*) FROM videos WHERE pic_source IS NOT NULL")
        local_poster = await conn.fetchval("SELECT count(*) FROM videos WHERE poster_path IS NOT NULL")
        state = await conn.fetchrow("SELECT * FROM sync_state WHERE id = 1")
        runs = await conn.fetch(
            "SELECT id, mode, status, started_at, finished_at, pages_done, "
            "items_upserted, items_failed, error FROM sync_runs ORDER BY id DESC LIMIT 10"
        )
    return {
        "videos": int(total),
        "withPoster": int(with_poster),
        "cachedPoster": int(local_poster),
        "state": dict(state) if state else None,
        "runs": [dict(run) for run in runs],
        "sourceDomains": list(settings.source_domains),
        "baseUrl": _base_url(request),
    }


@router.post("/admin/sync")
async def admin_sync(
    mode: str = Query("incremental", pattern="^(incremental|full|refresh)$"),
    pages: int | None = Query(None, ge=1),
    x_admin_token: str | None = Header(default=None),
):
    _require_admin(x_admin_token)
    pool = await db.get_pool()
    await pool.execute(
        "UPDATE sync_settings SET requested_mode = %s, requested_at = NOW(), "
        "updated_at = NOW() WHERE id = 1",
        mode,
    )
    return {"status": "queued", "mode": mode}
