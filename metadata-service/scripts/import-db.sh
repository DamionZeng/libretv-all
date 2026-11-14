#!/usr/bin/env bash
# 在服务器上恢复本地导出的 pg_dump 文件。
#   PGPASSWORD=xxx PGUSER=postgres PGDATABASE=libretv_metadata \
#     bash scripts/import-db.sh /tmp/libretv_metadata_20261114_1200.dump --recreate
#
# --recreate：先断开连接、删库重建，再导入（推荐，避免旧数据残留）。
# 不加则对已有库做覆盖式恢复（--clean --if-exists）。
set -euo pipefail

DUMP="${1:?用法: import-db.sh <dump文件> [--recreate]}"
MODE="${2:-}"
DB="${PGDATABASE:-libretv_metadata}"
DBUSER="${PGUSER:-postgres}"
DBHOST="${PGHOST:-127.0.0.1}"
DBPORT="${PGPORT:-5432}"

export PGHOST="$DBHOST" PGPORT="$DBPORT" PGUSER="$DBUSER"

[ -f "$DUMP" ] || { echo "[import] 找不到文件: $DUMP" >&2; exit 1; }

if [ "$MODE" = "--recreate" ]; then
  echo "[import] 断开 $DB 现有连接并重建数据库"
  PGDATABASE=postgres psql -v ON_ERROR_STOP=1 -q -c \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$DB' AND pid <> pg_backend_pid();"
  PGDATABASE=postgres psql -v ON_ERROR_STOP=1 -q -c "DROP DATABASE IF EXISTS \"$DB\";"
  PGDATABASE=postgres psql -v ON_ERROR_STOP=1 -q -c "CREATE DATABASE \"$DB\" OWNER \"$DBUSER\";"
  echo "[import] 恢复 $DUMP → $DB"
  pg_restore --no-owner --no-privileges -d "$DB" "$DUMP"
else
  echo "[import] 覆盖式恢复 $DUMP → $DB"
  pg_restore --no-owner --no-privileges --clean --if-exists -d "$DB" "$DUMP" \
    || echo "[import] pg_restore 返回非 0（通常是已有对象告警），继续校验"
fi

# 相似度检索依赖，超级用户才能建；失败只提示
PGDATABASE="$DB" psql -q -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;" 2>/dev/null \
  || echo "[import] 提示：pg_trgm 需用数据库超级用户执行 CREATE EXTENSION IF NOT EXISTS pg_trgm;"

echo "[import] 统计信息"
PGDATABASE="$DB" psql -q -c "ANALYZE;"
COUNT="$(PGDATABASE="$DB" psql -tAc 'SELECT count(*) FROM videos;')"
echo "[import] videos 行数: $COUNT"
echo "[import] 完成"
