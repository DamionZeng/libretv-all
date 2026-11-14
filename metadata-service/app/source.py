from __future__ import annotations

import asyncio
import logging
import random
from urllib.parse import urljoin

import httpx
from bs4 import BeautifulSoup

from app.config import settings

log = logging.getLogger(__name__)

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
RETRYABLE_STATUS = {403, 408, 425, 429, 451, 500, 502, 503, 504}


class SourceError(RuntimeError):
    pass


class RateLimiter:
    """按固定间隔放行请求，避免对采集源造成突发压力。"""

    def __init__(self, rps: float) -> None:
        self._interval = 1.0 / max(0.2, rps)
        self._next_at = 0.0
        self._lock = asyncio.Lock()

    async def wait(self) -> None:
        async with self._lock:
            now = asyncio.get_running_loop().time()
            delay = self._next_at - now
            if delay > 0:
                await asyncio.sleep(delay)
                now = asyncio.get_running_loop().time()
            self._next_at = max(now, self._next_at) + self._interval


class SourceClient:
    """暴风资源采集客户端：域名故障切换、限速、重试与详情页图片兜底。"""

    def __init__(self) -> None:
        self._domains = list(settings.source_domains)
        self._current = 0
        self._failures: dict[str, int] = {domain: 0 for domain in self._domains}
        self._last_error: str | None = None
        self._limiter = RateLimiter(settings.request_rps)
        self._semaphore = asyncio.Semaphore(settings.source_concurrency)
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.http_timeout, connect=min(10.0, settings.http_timeout)),
            follow_redirects=True,
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "application/json, text/html;q=0.9, */*;q=0.8",
                "Accept-Language": "zh-CN,zh;q=0.9",
                "Referer": self._domains[0] + "/",
            },
            limits=httpx.Limits(max_connections=settings.source_concurrency,
                                max_keepalive_connections=settings.source_concurrency),
        )

    @property
    def domain(self) -> str:
        return self._domains[self._current]

    @property
    def status(self) -> dict:
        return {
            "active": self.domain,
            "domains": self._domains,
            "failures": self._failures,
            "lastError": self._last_error,
        }

    async def close(self) -> None:
        await self._client.aclose()

    def _switch_domain(self, failed: str) -> None:
        self._failures[failed] = self._failures.get(failed, 0) + 1
        if len(self._domains) > 1:
            self._current = (self._current + 1) % len(self._domains)
        log.warning("source domain %s failed, switching to %s", failed, self.domain)

    async def _get(self, path: str, params: dict | None = None) -> httpx.Response:
        last_error: Exception | None = None
        attempts = settings.http_retries * max(1, len(self._domains))
        for attempt in range(attempts):
            domain = self.domain
            try:
                await self._limiter.wait()
                async with self._semaphore:
                    response = await self._client.get(
                        urljoin(domain + "/", path.lstrip("/")), params=params
                    )
                if response.status_code in RETRYABLE_STATUS:
                    raise SourceError(f"HTTP {response.status_code} from {domain}")
                response.raise_for_status()
                return response
            except (httpx.HTTPError, SourceError) as exc:
                last_error = exc
                self._last_error = f"{domain}: {exc}"
                self._switch_domain(domain)
                if attempt + 1 < attempts:
                    await asyncio.sleep(min(8.0, 0.5 * (2 ** attempt)) + random.random() * 0.4)
        raise SourceError(f"all source domains failed: {last_error}")

    async def fetch_page(self, page: int, page_size: int | None = None) -> dict:
        response = await self._get(
            "/api.php/provide/vod/",
            {"ac": "detail", "pg": max(1, page), "pagesize": page_size or settings.site_page_size},
        )
        try:
            payload = response.json()
        except ValueError as exc:
            raise SourceError("source returned invalid json") from exc
        if not isinstance(payload, dict) or payload.get("code") != 1:
            raise SourceError(f"source api error: {str(payload)[:200]}")
        if not isinstance(payload.get("list"), list):
            raise SourceError("source api missing list")
        return payload

    async def fetch_detail_poster(self, vod_id: int) -> str | None:
        """详情页兜底：接口没有封面时，从详情页 HTML 中提取图片地址。"""
        try:
            response = await self._get(f"/index.php/vod/detail/id/{vod_id}.html")
        except SourceError as exc:
            log.warning("detail page failed for %s: %s", vod_id, exc)
            return None
        html = response.text
        soup = BeautifulSoup(html, "html.parser")
        image = soup.select_one("div.people div.left img")
        candidate = None
        if image is not None:
            candidate = image.get("src") or image.get("data-original") or image.get("data-src")
        if not candidate:
            meta = soup.select_one('meta[property="og:image"]')
            if meta is not None:
                candidate = meta.get("content")
        if not candidate:
            return None
        candidate = candidate.strip()
        if candidate.startswith("//"):
            return "https:" + candidate
        return urljoin(self.domain + "/", candidate)
