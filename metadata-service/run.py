#!/usr/bin/env python3
"""统一启动入口，供宝塔 Python 项目 / 命令行使用。

    python run.py

等价于:
    uvicorn app.main:app --host <API_HOST> --port <API_PORT>

放在项目根目录，确保无论从哪个工作目录启动，`app` 包都能被导入。
"""
from __future__ import annotations

import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import uvicorn  # noqa: E402

from app.config import settings  # noqa: E402
from app.main import app  # noqa: E402,F401  供 gunicorn 用 run:app

if __name__ == "__main__":
    uvicorn.run(app, host=settings.api_host, port=settings.api_port, access_log=False)
