#!/usr/bin/env python3
"""统一启动入口，供宝塔 Python 项目 / 命令行使用。

    python run.py

等价于:
    uvicorn app.main:app --host <API_HOST> --port <API_PORT>

放在项目根目录，确保无论从哪个工作目录启动，`app` 包都能被导入。
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import uvicorn  # noqa: E402

from app.config import settings  # noqa: E402
from app.main import app  # noqa: E402,F401  供 gunicorn 用 run:app

if __name__ == "__main__":
    # 面板可能通过 PORT 环境变量注入端口，优先使用
    host = os.getenv("API_HOST", settings.api_host)
    port = int(os.getenv("PORT", settings.api_port))
    uvicorn.run(app, host=host, port=port, access_log=False)
