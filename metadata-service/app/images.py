from __future__ import annotations

import asyncio
import logging
import os
from pathlib import Path

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse, RedirectResponse

from app import db
from app.config import settings

log = logging.getLogger(__name__)
router = APIRouter()

_extension_by_type = {
    "image/jpeg": "jpg",
    "image/jpg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
    "image/gif": "gif",
    "image/avif": "avif",
}
_allowed_extensions = {"jpg", "jpeg", "png", "webp", "gif", "avif"}
_locks: dict[int, asyncio.Lock] = {}
_locks_guard = asyncio.Lock()


async def _lock_for(video_id: int) -> asyncio.Lock:
    async with _locks_guard:
        return _locks.setdefault(video_id, asyncio.Lock())


def _target_path(video_id: int, extension: str) -> Path:
    folder = settings.image_cache_dir / f"{video_id % 1000:03d}"
    folder.mkdir(parents=True, exist_ok=True)
    return folder / f"{video_id}.{extension}"


def _extension(url: str, content_type: str | None) -> str:
    suffix = Path(url.split("?")[0]).suffix.lower().lstrip(".")
    if suffix in _allowed_extensions:
        return "jpg" if suffix == "jpeg" else suffix
    return _extension_by_type.get((content_type or "").split(";")[0].strip(), "jpg")


async def _download(video_id: int, url: str, client: httpx.AsyncClient) -> Path | None:
    lock = await _lock_for(video_id)
    async with lock:
        pool = await db.get_pool()
        row = await pool.fetchrow("SELECT poster_path FROM videos WHERE id = $1", video_id)
        if row and row["poster_path"]:
            cached = settings.image_cache_dir / row["poster_path"]
            if cached.exists():
                return cached

        try:
            async with client.stream("GET", url, headers={"Referer": url}) as response:
                response.raise_for_status()
                content_type = response.headers.get("content-type", "")
                if not content_type.lower().startswith("image/"):
                    return None
                extension = _extension(url, content_type)
                target = _target_path(video_id, extension)
                buffer = bytearray()
                async for chunk in response.aiter_bytes():
                    buffer.extend(chunk)
                    if len(buffer) > settings.image_max_bytes:
                        log.warning("poster %s exceeds size limit", video_id)
                        return None
                if not buffer:
                    return None
                temp = target.with_suffix(target.suffix + ".tmp")
                temp.write_bytes(bytes(buffer))
                os.replace(temp, target)
        except (httpx.HTTPError, OSError) as exc:
            log.warning("poster %s download failed: %s", video_id, exc)
            return None

        relative = target.relative_to(settings.image_cache_dir)
        await pool.execute(
            "UPDATE videos SET poster_path = $1 WHERE id = $2 AND pic_source = $3",
            str(relative), video_id, url,
        )
        return target


async def _warm(video_id: int, url: str, client: httpx.AsyncClient) -> None:
    """后台补缓存，失败不影响本次响应。"""
    try:
        await _download(video_id, url, client)
    except Exception:  # noqa: BLE001 - 后台任务不能把异常抛给事件循环
        log.debug("poster %s warm failed", video_id, exc_info=True)


_warm_tasks: set[asyncio.Task] = set()


def _schedule_warm(video_id: int, url: str, client: httpx.AsyncClient) -> None:
    # 保留强引用，避免任务被垃圾回收后不再执行
    task = asyncio.create_task(_warm(video_id, url, client))
    _warm_tasks.add(task)
    task.add_done_callback(_warm_tasks.discard)


@router.get("/media/posters/{video_id}", include_in_schema=False)
async def get_poster(video_id: int, request: Request):
    pool = await db.get_pool()
    row = await pool.fetchrow(
        "SELECT id, pic_source, poster_path FROM videos WHERE id = $1", video_id
    )
    if row is None:
        raise HTTPException(status_code=404, detail="video not found")

    if row["poster_path"]:
        cached = settings.image_cache_dir / row["poster_path"]
        if cached.exists():
            return FileResponse(cached, headers={"Cache-Control": "public, max-age=604800"})

    source = row["pic_source"]
    if not source:
        raise HTTPException(status_code=404, detail="poster unavailable")

    client: httpx.AsyncClient | None = getattr(request.app.state, "image_client", None)
    if client is None:
        raise HTTPException(status_code=503, detail="image client unavailable")

    # 未缓存时先让设备直连图源，避免整屏海报排队等下载导致超时显示占位图；
    # 同时后台把图片落到本地，下次访问就走缓存。
    _schedule_warm(video_id, source, client)
    return RedirectResponse(source, status_code=302,
                            headers={"Cache-Control": "public, max-age=3600"})
