#!/usr/bin/env bash
# 服务器端部署脚本：由 GitHub Actions 通过 SSH 调用，也可手动执行。
#   bash /www/wwwroot/libretv-metadata/scripts/deploy-remote.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"
echo "[deploy] 工作目录: $DIR"

# 运行时目录（日志、海报缓存），首次部署时自动创建
mkdir -p data/posters

# 定位虚拟环境：优先 $VENV_PATH，其次项目自建 .venv，最后用宝塔 Python 项目生成的 xxx_venv
VENV="${VENV_PATH:-}"
if [ -z "$VENV" ]; then
  for candidate in "$DIR/.venv" "$DIR"/*_venv; do
    if [ -x "$candidate/bin/pip" ]; then
      VENV="$candidate"
      break
    fi
  done
fi
if [ -z "$VENV" ]; then
  echo "[deploy] 未找到虚拟环境。请先在宝塔 Python 项目中创建项目，或设置 VENV_PATH" >&2
  exit 1
fi
echo "[deploy] 虚拟环境: $VENV"

echo "[deploy] 安装依赖"
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q -r "$DIR/requirements.txt"

echo "[deploy] 重启服务"
bash "$DIR/scripts/restart-service.sh"
