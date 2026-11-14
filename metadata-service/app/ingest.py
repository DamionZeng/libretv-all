from __future__ import annotations

import html as html_lib
import re
from datetime import datetime, timedelta, timezone
from typing import Any

CST = timezone(timedelta(hours=8))

# 与 App 卡片识别用的类型词保持一致
GENRE_KEYWORDS = {
    "真人秀", "脱口秀", "纪录片", "动画", "剧情", "喜剧", "动作", "爱情",
    "科幻", "悬疑", "惊悚", "恐怖", "犯罪", "同性", "音乐", "歌舞", "传记",
    "历史", "战争", "西部", "奇幻", "冒险", "灾难", "武侠", "古装", "运动",
    "家庭", "儿童", "短片", "情色", "鬼怪", "青春", "校园", "职场", "都市",
    "励志", "生活", "美食", "旅行", "访谈", "综艺",
}

TYPE_GENRE = {
    21: "动作", 22: "喜剧", 23: "恐怖", 24: "科幻", 25: "爱情",
    26: "剧情", 27: "战争", 28: "纪录片", 50: "动画",
    31: "剧情", 32: "剧情", 33: "剧情", 34: "剧情", 35: "剧情",
    36: "剧情", 37: "剧情", 38: "剧情",
    40: "动画", 41: "动画", 42: "动画", 43: "动画", 44: "动画",
    46: "综艺", 47: "综艺", 48: "综艺", 49: "综艺",
    58: "短片", 59: "短片", 60: "短片", 61: "短片", 62: "短片",
    63: "短片", 64: "短片", 65: "短片", 66: "短片", 67: "短片",
    68: "短片",
}

YEAR_RE = re.compile(r"^(19|20)\d{2}$")
END_RE = re.compile(r"(完结|全\s*\d+\s*集)")

UPSERT_SQL = """
INSERT INTO videos (
    id, name, sub_name, en_name, type_id, type_id_1, type_name, vod_class,
    genres, year, area, lang, remarks, score, score_all, score_num,
    douban_id, douban_score, actor, director, writer, blurb, content,
    pic_source, poster_path, hits, hits_day, hits_week, hits_month,
    serial, total, is_end, pubdate, duration, episode_count,
    source_updated_at, fetched_at, updated_at
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8,
    $9, $10, $11, $12, $13, $14, $15, $16,
    $17, $18, $19, $20, $21, $22, $23,
    $24, NULL, $25, $26, $27, $28,
    $29, $30, $31, $32, $33, $34,
    $35, now(), now()
)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    sub_name = EXCLUDED.sub_name,
    en_name = EXCLUDED.en_name,
    type_id = EXCLUDED.type_id,
    type_id_1 = EXCLUDED.type_id_1,
    type_name = EXCLUDED.type_name,
    vod_class = EXCLUDED.vod_class,
    genres = EXCLUDED.genres,
    year = EXCLUDED.year,
    area = EXCLUDED.area,
    lang = EXCLUDED.lang,
    remarks = EXCLUDED.remarks,
    score = EXCLUDED.score,
    score_all = EXCLUDED.score_all,
    score_num = EXCLUDED.score_num,
    douban_id = EXCLUDED.douban_id,
    douban_score = EXCLUDED.douban_score,
    actor = EXCLUDED.actor,
    director = EXCLUDED.director,
    writer = EXCLUDED.writer,
    blurb = EXCLUDED.blurb,
    content = EXCLUDED.content,
    pic_source = EXCLUDED.pic_source,
    poster_path = CASE
        WHEN videos.pic_source IS DISTINCT FROM EXCLUDED.pic_source THEN NULL
        ELSE videos.poster_path
    END,
    hits = EXCLUDED.hits,
    hits_day = EXCLUDED.hits_day,
    hits_week = EXCLUDED.hits_week,
    hits_month = EXCLUDED.hits_month,
    serial = EXCLUDED.serial,
    total = EXCLUDED.total,
    is_end = EXCLUDED.is_end,
    pubdate = EXCLUDED.pubdate,
    duration = EXCLUDED.duration,
    episode_count = EXCLUDED.episode_count,
    source_updated_at = EXCLUDED.source_updated_at,
    fetched_at = now(),
    updated_at = CASE
        WHEN ROW(videos.name, videos.pic_source, videos.source_updated_at,
                 videos.score, videos.remarks)
             IS DISTINCT FROM
             ROW(EXCLUDED.name, EXCLUDED.pic_source, EXCLUDED.source_updated_at,
                 EXCLUDED.score, EXCLUDED.remarks)
        THEN now()
        ELSE videos.updated_at
    END
"""


def _text(value: Any, limit: int | None = None) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return text[:limit] if limit else text


def _int(value: Any) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _score(*values: Any) -> float | None:
    for value in values:
        try:
            number = float(value)
        except (TypeError, ValueError):
            continue
        if 0 < number <= 10:
            return round(number, 1)
    return None


def _strip_html(value: Any) -> str | None:
    if value is None:
        return None
    text = re.sub(r"<[^>]+>", " ", str(value))
    text = html_lib.unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    return text or None


def _year(value: Any) -> int | None:
    text = str(value or "").strip()
    if YEAR_RE.match(text):
        return int(text)
    return None


def _picture(value: Any, base_url: str) -> str | None:
    text = str(value or "").strip()
    if not text:
        return None
    if text.startswith("//"):
        return "https:" + text
    if text.startswith("/"):
        return base_url.rstrip("/") + text
    if text.startswith(("http://", "https://")):
        return text
    return None


def _genres(item: dict) -> list[str]:
    found: list[str] = []
    for raw in (item.get("vod_class"), item.get("type_name")):
        for token in re.split(r"[,，、/|\s]+", str(raw or "")):
            token = token.strip()
            if token in GENRE_KEYWORDS and token not in found:
                found.append(token)
    mapped = TYPE_GENRE.get(_int(item.get("type_id")) or -1)
    if mapped and mapped not in found:
        found.append(mapped)
    return found[:4]


def _source_time(value: Any) -> datetime | None:
    text = str(value or "").strip()
    if not text:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(text, fmt).replace(tzinfo=CST).astimezone(timezone.utc)
        except ValueError:
            continue
    return None


def _episode_count(item: dict) -> int | None:
    play_url = str(item.get("vod_play_url") or "")
    if play_url:
        return max(1, play_url.count("#") + 1)
    serial = _int(item.get("vod_serial"))
    return serial if serial and serial > 0 else None


def normalize(item: dict, base_url: str, poster: str | None = None) -> tuple:
    vod_id = _int(item.get("vod_id"))
    if not vod_id:
        raise ValueError("missing vod_id")
    name = _text(item.get("vod_name"), 300)
    if not name:
        raise ValueError("missing vod_name")

    remarks = _text(item.get("vod_remarks"), 200)
    is_end = _int(item.get("vod_isend")) == 1 or bool(remarks and END_RE.search(remarks))
    douban_score = _score(item.get("vod_douban_score"))
    display_score = _score(item.get("vod_douban_score"), item.get("vod_score"))

    return (
        vod_id,                                        # $1
        name,                                          # $2
        _text(item.get("vod_sub"), 300),               # $3
        _text(item.get("vod_en"), 300),                # $4
        _int(item.get("type_id")),                     # $5
        _int(item.get("type_id_1")),                   # $6
        _text(item.get("type_name"), 100),             # $7
        _text(item.get("vod_class"), 300),             # $8
        _genres(item),                                 # $9
        _year(item.get("vod_year")),                   # $10
        _text(item.get("vod_area"), 100),              # $11
        _text(item.get("vod_lang"), 100),              # $12
        remarks,                                       # $13
        display_score,                                 # $14
        _int(item.get("vod_score_all")),               # $15
        _int(item.get("vod_score_num")),               # $16
        _int(item.get("vod_douban_id")),               # $17
        douban_score,                                  # $18
        _text(item.get("vod_actor"), 2000),            # $19
        _text(item.get("vod_director"), 500),          # $20
        _text(item.get("vod_writer"), 500),            # $21
        _text(item.get("vod_blurb"), 500),             # $22
        _text(_strip_html(item.get("vod_content")), 8000),  # $23
        _picture(poster or item.get("vod_pic"), base_url),  # $24
        _int(item.get("vod_hits")),                    # $25
        _int(item.get("vod_hits_day")),                # $26
        _int(item.get("vod_hits_week")),               # $27
        _int(item.get("vod_hits_month")),              # $28
        _int(item.get("vod_serial")),                  # $29
        _int(item.get("vod_total")),                   # $30
        is_end,                                        # $31
        _text(item.get("vod_pubdate"), 100),           # $32
        _text(item.get("vod_duration"), 100),          # $33
        _episode_count(item),                          # $34
        _source_time(item.get("vod_time")),            # $35
    )


def replace_poster(row: tuple, poster: str | None) -> tuple:
    if not poster:
        return row
    values = list(row)
    values[23] = poster
    return tuple(values)
