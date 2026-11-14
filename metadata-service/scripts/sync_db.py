#!/usr/bin/env python3
"""一条命令把本地数据库同步到服务器：导出 -> 上传 -> 远端重建导入。

用法:
    python scripts/sync_db.py --server root@1.2.3.4
    python scripts/sync_db.py --server root@1.2.3.4 --port 2222 --db-password 密码
    python scripts/sync_db.py --server root@1.2.3.4 --dump data/backup/xxx.dump   # 复用已有 dump

前提：本机能免密 ssh/scp 登录服务器，服务器已装 PostgreSQL 客户端（pg_restore/psql）。
"""
from __future__ import annotations

import argparse
import shlex
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from export_db import ROOT, export  # noqa: E402


def run(command: list[str]) -> None:
    print("[sync] " + " ".join(command))
    if subprocess.run(command).returncode != 0:
        raise SystemExit(f"命令执行失败: {command[0]}")


def main() -> None:
    parser = argparse.ArgumentParser(description="同步本地数据库到服务器")
    parser.add_argument("--server", required=True, help="user@host")
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--remote-dir", default="/www/wwwroot/libretv-metadata")
    parser.add_argument("--database", default="libretv_metadata")
    parser.add_argument("--db-user", default="postgres")
    parser.add_argument("--db-password", default="postgres")
    parser.add_argument("--container", default="my-postgres")
    parser.add_argument("--dump", default="", help="复用已有 dump 文件，跳过导出")
    args = parser.parse_args()

    if args.dump:
        dump = Path(args.dump).resolve()
        if not dump.is_file():
            raise SystemExit(f"找不到导出文件: {dump}")
    else:
        dump = export(args.database, args.db_user, ROOT / "data" / "backup", args.container)

    remote_dump = f"/tmp/{dump.name}"
    print(f"[sync] 上传 {dump.name} 到 {args.server}")
    run(["scp", "-P", str(args.port), str(dump), f"{args.server}:{remote_dump}"])

    env = " ".join(
        [
            f"PGPASSWORD={shlex.quote(args.db_password)}",
            "PGHOST=127.0.0.1",
            "PGPORT=5432",
            f"PGUSER={shlex.quote(args.db_user)}",
            f"PGDATABASE={shlex.quote(args.database)}",
        ]
    )
    script = shlex.quote(f"{args.remote_dir}/scripts/import-db.sh")
    remote_command = f"{env} bash {script} {shlex.quote(remote_dump)} --recreate"

    print(f"[sync] 服务器重建数据库 {args.database} 并导入")
    run(["ssh", "-p", str(args.port), args.server, remote_command])

    subprocess.run(["ssh", "-p", str(args.port), args.server, f"rm -f {shlex.quote(remote_dump)}"])
    print("[sync] 完成，服务器数据库已与本地一致")


if __name__ == "__main__":
    main()
