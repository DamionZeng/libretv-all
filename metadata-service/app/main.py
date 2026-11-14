from __future__ import annotations

import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager
from logging.handlers import TimedRotatingFileHandler

import httpx
from fastapi import FastAPI

from app import admin, api, db, images
from app.config import settings
from app.source import USER_AGENT
from app.sync import daemon, mark_stale_runs

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger(__name__)

# 供后台管理页查看的日志文件：按天轮转，只保留最近 30 天
settings.log_file.parent.mkdir(parents=True, exist_ok=True)
_file_handler = TimedRotatingFileHandler(
    settings.log_file, when="midnight", interval=1, backupCount=30, encoding="utf-8"
)
_file_handler.setFormatter(
    logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
)
logging.getLogger().addHandler(_file_handler)


@asynccontextmanager
async def lifespan(app: FastAPI):
    pool = await db.init_pool()
    await db.ensure_schema(pool)
    app.state.image_client = httpx.AsyncClient(
        timeout=httpx.Timeout(settings.http_timeout),
        follow_redirects=True,
        headers={"User-Agent": USER_AGENT, "Accept": "image/*,*/*;q=0.8"},
        limits=httpx.Limits(max_connections=8, max_keepalive_connections=8),
    )
    task = None
    if settings.sync_enabled:
        await mark_stale_runs()
        task = asyncio.create_task(daemon())
    log.info("metadata api ready on %s:%s", settings.api_host, settings.api_port)
    try:
        yield
    finally:
        if task is not None:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        await app.state.image_client.aclose()
        await db.close_pool()


app = FastAPI(
    title="LibreTV Metadata Service",
    description="暴风资源元数据采集 + 豆瓣兼容接口",
    version="0.1.0",
    lifespan=lifespan,
)

app.include_router(api.router)
app.include_router(admin.router)
app.include_router(images.router)


@app.get("/", include_in_schema=False)
async def root():
    return {
        "service": "libretv-metadata",
        "docs": "/docs",
        "health": "/health",
        "admin": "/admin",
        "compatible": "/rexxar/api/v2/movie/recommend",
    }
