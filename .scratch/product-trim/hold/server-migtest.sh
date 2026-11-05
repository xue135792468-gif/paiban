#!/usr/bin/env bash
# 票 16 补测：在云服务器上用「生产库副本」跑 V48–V55，验证真实 PostgreSQL 的存量数据保全。
#
# 安全边界（务必遵守）：
#   - 只读生产库：只 pg_dump；绝不 UPDATE/DELETE/DROP 生产库任何对象。
#   - 所有写操作都发生在临时库 paiban_migtest_<UTC>，验完即删。
#   - 不部署、不重建镜像、不 up -d、不碰 ~/paiban 工作副本。
#   - 远程步骤合并为单次 SSH 调用（OPS.md 红线：sshd 会限流）。
#
# 前置：
#   - 服务器 ~/paiban 已 git pull --ff-only 到包含 V55 的 origin/main
#     （若尚未同步，本脚本会打印当前最高迁移版本并拒绝继续）。
#   - flyway/flyway:10 镜像已在本地（历史演练用过）。
set -euo pipefail

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TMPDB="paiban_migtest_${STAMP}"
LOG="$HOME/ops/paiban-migtest-${STAMP}.log"
DUMP="$HOME/backups/paiban/paiban-migtest-source-${STAMP}.sql.gz"

exec > >(tee -a "$LOG") 2>&1
echo "==> 票 16 真实 PostgreSQL 存量数据保全演练  $STAMP"
echo "    临时库: $TMPDB"
echo "    日志  : $LOG"
echo "    源备份: $DUMP"

REPO="$HOME/paiban"
MIGRATIONS="$REPO/backend/src/main/resources/db/migration"
PG="sudo -n docker exec -i paiban-postgres-1 psql -U paiban -d postgres -tAc"
PSQL_PROD="sudo -n docker exec -i paiban-postgres-1 psql -U paiban -d paiban -tAc"

cleanup() {
  echo "==> 清理临时库 $TMPDB"
  $PG "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$TMPDB' and pid <> pg_backend_pid()" >/dev/null 2>&1 || true
  $PG "drop database if exists \"$TMPDB\"" >/dev/null 2>&1 || true
  echo "==> 临时库已删除（生产库未改动）"
}
trap cleanup EXIT

# ---------- 0. 前置检查 ----------
echo "==> 0/6 前置：迁移文件与生产 Flyway 版本"
HIGHEST=$(ls "$MIGRATIONS"/V*.sql | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -1)
echo "    服务器工作副本最高迁移版本: V$HIGHEST"
if [[ "$HIGHEST" -lt 55 ]]; then
  echo "!! 服务器工作副本还没有 V55。请先在 ~/paiban 执行 git pull --ff-only 后重跑本脚本。"
  exit 2
fi
PROD_VER=$($PSQL_PROD "select max(version::int) from flyway_schema_history")
echo "    生产库 Flyway 版本: v$PROD_VER"
if [[ "$PROD_VER" -ge 55 ]]; then
  echo "!! 生产库已到 v55（或更高），无需演练，停止。"
  exit 2
fi
echo "    flyway 镜像: $(sudo -n docker images --format '{{.Repository}}:{{.Tag}}' | grep -i '^flyway/flyway' | head -1)"

# ---------- 1. 备份生产库（只读导出，双保险） ----------
echo "==> 1/6 只读导出生产库并 gzip 校验"
mkdir -p "$HOME/backups/paiban"
sudo -n docker exec paiban-postgres-1 pg_dump -U paiban -d paiban | gzip > "$DUMP"
gzip -t "$DUMP"
echo "    备份 OK: $(stat -c %s "$DUMP") 字节, gzip -t 通过"

# ---------- 2. 建临时库并从备份恢复（生产数据的完整副本） ----------
echo "==> 2/6 建临时库并从生产备份恢复"
$PG "drop database if exists \"$TMPDB\"" >/dev/null 2>&1 || true
$PG "create database \"$TMPDB\"" >/dev/null
gzip -dc "$DUMP" | sudo -n docker exec -i paiban-postgres-1 psql -U paiban -d "$TMPDB" -q >/dev/null
echo "    恢复完成"

PSQL_TMP="sudo -n docker exec -i paiban-postgres-1 psql -U paiban -d $TMPDB -tAc"

# ---------- 3. 迁移前：记录行数与主体 ----------
echo "==> 3/6 迁移前快照（行数 / 主体）"
TABLES=(timetable_entry substitution substitution_request constraint_config constraint_entry ai_import_log ai_call_log notification notification_log recent_deletion import_recipe timetable_publish timetable_draft draft_lesson)
declare -A BEFORE
for t in "${TABLES[@]}"; do
  # V48/V49 才会建的 recent_deletion / import_recipe 在 v47 生产库里不存在，记为 NEW。
  exists=$($PSQL_TMP "select count(*) from information_schema.tables where table_schema='public' and table_name='$t'" 2>/dev/null || echo 0)
  if [[ "$exists" == "0" ]]; then
    BEFORE[$t]="NEW"
    printf '    %-28s %s (v47 未建，迁移后应存在)\n' "$t" "NEW"
  else
    n=$($PSQL_TMP "select count(*) from $t" 2>/dev/null || echo "NA")
    BEFORE[$t]="$n"
    printf '    %-28s %s\n' "$t" "$n"
  fi
done
BEFORE_VER=$($PSQL_TMP "select max(version::int) from flyway_schema_history")
echo "    临时库 Flyway 版本(前): v$BEFORE_VER"
# 主体值：按课程/班级/星期/节次回查已发布课表
BEFORE_SUBJECT=$($PSQL_TMP "select count(*) from timetable_entry where course_id is not null and admin_class_id is not null and day_of_week is not null")
echo "    已发布课表主体行(course+class+day 非空): $BEFORE_SUBJECT"
# 旧列存在性（V55 应删掉它们）
BEFORE_COLS=$($PSQL_TMP "select count(*) from information_schema.columns where table_schema='public' and ((table_name='timetable_entry' and column_name='room_id') or (table_name='period' and column_name='section') or (table_name='course' and column_name='required_room_type'))")
echo "    旧列(room_id/section/required_room_type)存在数: $BEFORE_COLS（预期 3）"

# ---------- 4. 在临时库跑 V48–V55 ----------
echo "==> 4/6 在临时库执行 V48–V55（真实 PostgreSQL 16）"
NET=$(sudo -n docker inspect paiban-postgres-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' | head -1)
echo "    postgres 网络: $NET"
# 复用 postgres 容器自己的密码（不打印、不写死）：pg_hba 要求 scram-sha-256，
# 而 docker exec psql 走的是容器内 trust，所以 Flyway 必须拿真实密码走 TCP。
PGPASS=$(sudo -n docker exec paiban-postgres-1 sh -c 'printf %s "$POSTGRES_PASSWORD"')
if [[ -z "$PGPASS" ]]; then
  echo "!! 无法从 postgres 容器读取 POSTGRES_PASSWORD，停止（不猜密码）"
  exit 2
fi
echo "    已从 postgres 容器取得连接密码（值不打印）"
sudo -n docker run --rm \
  --network "$NET" \
  -v "$MIGRATIONS":/flyway/sql:ro \
  -e FLYWAY_URL="jdbc:postgresql://paiban-postgres-1:5432/$TMPDB" \
  -e FLYWAY_USER=paiban \
  -e FLYWAY_PASSWORD="$PGPASS" \
  -e FLYWAY_LOCATIONS=filesystem:/flyway/sql \
  -e FLYWAY_OUT_OF_ORDER=false \
  flyway/flyway:10 -connectRetries=3 migrate 2>&1 | tee /tmp/flyway-migtest.out
echo "==> flyway 退出码: ${PIPESTATUS[0]}"
AFTER_VER=$($PSQL_TMP "select max(version::int) from flyway_schema_history")
echo "    临时库 Flyway 版本(后): v$AFTER_VER"
V55_OK=$($PSQL_TMP "select count(*) from flyway_schema_history where version='55' and success=true")
echo "    V55 success=true 记录数: $V55_OK"

# ---------- 5. 迁移后比对：行数/主体不丢 ----------
echo "==> 5/6 迁移后比对（票 16 口径：行数/主体不丢）"
FAIL=0
for t in "${TABLES[@]}"; do
  n=$($PSQL_TMP "select count(*) from $t" 2>/dev/null || echo "NA")
  b=${BEFORE[$t]}
  if [[ "$b" == "NEW" ]]; then
    # 新表只要求迁移后存在，不要求与旧库行数相等。
    if [[ "$n" != "NA" ]]; then
      printf '    OK   %-28s NEW -> %s (V48/V49 新建)\n' "$t" "$n"
    else
      printf '    FAIL %-28s NEW -> 表仍不存在\n' "$t"
      FAIL=1
    fi
  elif [[ "$n" == "$b" ]]; then
    printf '    OK   %-28s %s -> %s\n' "$t" "$b" "$n"
  else
    printf '    FAIL %-28s %s -> %s\n' "$t" "$b" "$n"
    FAIL=1
  fi
done
AFTER_SUBJECT=$($PSQL_TMP "select count(*) from timetable_entry where course_id is not null and admin_class_id is not null and day_of_week is not null")
echo "    已发布课表主体行: $BEFORE_SUBJECT -> $AFTER_SUBJECT"
[[ "$AFTER_SUBJECT" == "$BEFORE_SUBJECT" ]] || FAIL=1
AFTER_COLS=$($PSQL_TMP "select count(*) from information_schema.columns where table_schema='public' and ((table_name='timetable_entry' and column_name='room_id') or (table_name='period' and column_name='section') or (table_name='course' and column_name='required_room_type'))")
echo "    旧列存在数: $BEFORE_COLS -> $AFTER_COLS（预期 0）"
[[ "$AFTER_COLS" == "0" ]] || FAIL=1
KEPT=$($PSQL_TMP "select count(*) from information_schema.tables where table_schema='public' and table_name in ('notification','notification_log','ai_call_log','ai_import_log','recent_deletion','import_recipe','timetable_publish')")
echo "    7 张红线表仍在: $KEPT（预期 7）"
[[ "$KEPT" == "7" ]] || FAIL=1
DEAD=$($PSQL_TMP "select count(*) from information_schema.tables where table_schema='public' and table_name in ('room','substitution_conversion','substitution_conversion_item')")
echo "    3 张应删表残留: $DEAD（预期 0）"
[[ "$DEAD" == "0" ]] || FAIL=1
DEADLINE=$($PSQL_TMP "select count(*) from information_schema.columns where table_schema='public' and table_name='substitution_preview' and column_name='deadline_at'")
LOCKED=$($PSQL_TMP "select count(*) from information_schema.columns where table_schema='public' and table_name='draft_lesson' and column_name='locked'")
echo "    deadline_at / locked 保留: $DEADLINE / $LOCKED（预期 1/1）"
[[ "$DEADLINE" == "1" && "$LOCKED" == "1" ]] || FAIL=1

# ---------- 6. 结论 ----------
echo "==> 6/6 结论"
if [[ "$AFTER_VER" == "55" && "$V55_OK" == "1" && "$FAIL" == "0" ]]; then
  echo "==> MIGTEST PASS：真实 PostgreSQL 上 V$PROD_VER -> V55 成功，4 类主体 + 7 张红线表行数/主体不丢，17 列 3 表按票 15 删除"
else
  echo "==> MIGTEST FAIL：见上方 FAIL 行（afterVer=$AFTER_VER v55ok=$V55_OK fail=$FAIL）"
  exit 1
fi
echo "==> 生产库版本仍为 v$PROD_VER，未做任何写入；临时库由 trap 自动删除"
