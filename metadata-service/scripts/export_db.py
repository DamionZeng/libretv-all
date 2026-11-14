#!/usr/bin/env python3
"""导出本地 libretv_metadata 为 pg_dump 自定义格式（-Fc），供服务器导入。

用法:
    python scripts/export_db.py
    python scripts/export_db.py --container my-postgres --database libretv_metadata
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def running_container(name: str) -> str | None:
    """返回正在运行的容器名，找不到 docker 或容器时返回 None。"""
    if not shutil.which("docker"):
        return None
    try:
        out = subprocess.run(
            ["docker", "ps", "--filter", f"name={name}", "--format", "{{.Names}}"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (subprocess.CalledProcessError, OSError):
        return None
    return out.splitlines()[0] if out else None


def export(database: str, user: str, out_dir: Path, container: str) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"{database}_{datetime.now():%Y%m%d_%H%M}.dump"

    name = running_container(container)
    if name:
        command = ["docker", "exec", name, "pg_dump", "-U", user, "-Fc", database]
        print(f"[export] 从容器 {name} 导出 {database}")
    elif shutil.which("pg_dump"):
        command = ["pg_dump", "-U", user, "-Fc", database]
        print(f"[export] 使用本机 pg_dump 导出 {database}")
    else:
        raise SystemExit(f"未找到运行中的容器 {container}，本机也没有 pg_dump")

    # stdout 直接写文件句柄，避免管道破坏二进制内容
    with target.open("wb") as handle:
        result = subprocess.run(command, stdout=handle)

    if result.returncode != 0 or target.stat().st_size == 0:
        target.unlink(missing_ok=True)
        raise SystemExit("导出失败，请检查数据库是否在运行")

    size = target.stat().st_size / 1024 / 1024
    print(f"[export] 完成: {target} ({size:.1f} MB)")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description="导出 libretv 元数据库")
    parser.add_argument("--container", default="my-postgres", help="本地 PostgreSQL 容器名")
    parser.add_argument("--database", default="libretv_metadata")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--out-dir", default=str(ROOT / "data" / "backup"))
    args = parser.parse_args()

    target = export(args.database, args.user, Path(args.out_dir), args.container)
    print(target)


if __name__ == "__main__":
    main()
