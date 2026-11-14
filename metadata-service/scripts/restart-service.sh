#!/usr/bin/env bash
# 重启元数据服务。按 systemd → supervisor → 直接托管 uvicorn 的顺序自动选择。
#   bash scripts/restart-service.sh
#
# 宝塔 Python 项目方式运行时，它的进程不归 systemd/supervisor 管，
# 脚本会用 pkill + nohup 自行拉起（面板里会显示为“已停止”，仅能保证服务可用）。
# 想让 CI 部署与面板状态一致，建议改用 systemd 或 Supervisor 托管。
set -uo pipefail

NAME="libretv-metadata"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

read_env() {
  grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r' | xargs
}
HOST="$(read_env API_HOST)"; HOST="${HOST:-127.0.0.1}"
PORT="$(read_env API_PORT)"; PORT="${PORT:-8080}"
PROBE="$HOST"; [ "$PROBE" = "0.0.0.0" ] && PROBE="127.0.0.1"

if systemctl list-unit-files 2>/dev/null | grep -q "^${NAME}\.service"; then
  echo "[restart] systemctl restart $NAME"
  systemctl restart "$NAME"
  RESULT=$?
elif command -v supervisorctl >/dev/null 2>&1 && supervisorctl status "$NAME" >/dev/null 2>&1; then
  echo "[restart] supervisorctl restart $NAME"
  supervisorctl restart "$NAME"
  RESULT=$?
else
  VENV="${VENV_PATH:-}"
  if [ -z "$VENV" ]; then
    for candidate in "$DIR/.venv" "$DIR"/*_venv; do
      if [ -x "$candidate/bin/python" ]; then
        VENV="$candidate"
        break
      fi
    done
  fi
  if [ -z "$VENV" ]; then
    echo "[restart] 未找到虚拟环境，无法启动" >&2
    exit 1
  fi
  echo "[restart] 直接托管 uvicorn（$VENV）"
  pkill -f "uvicorn app.main:app" 2>/dev/null || true
  sleep 2
  nohup "$VENV/bin/python" -m uvicorn app.main:app \
    --host "$HOST" --port "$PORT" --no-access-log >> data/api.log 2>&1 &
  sleep 4
  RESULT=0
fi

if [ "$RESULT" -ne 0 ]; then
  echo "[restart] 重启命令执行失败" >&2
  exit "$RESULT"
fi

for _ in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS --max-time 3 "http://${PROBE}:${PORT}/health" >/dev/null 2>&1; then
    echo "[restart] 服务正常: http://${PROBE}:${PORT}"
    exit 0
  fi
  sleep 2
done

echo "[restart] 健康检查失败，请查看 data/api.log 或 data/service.log" >&2
exit 1
