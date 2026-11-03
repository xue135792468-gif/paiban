# 草稿换位九票部署回执

日期：2026-11-03（UTC 01:03 / 北京时间 09:03 左右）
授权：用户明确说“合并部署吧”。

## 合并

- PR：[#22](https://github.com/xue135792468-gif/paiban/pull/22) `feat: 草稿换位、锁定与指定草稿继续优化（九票）`
- main 合并提交：`92c36ab`（`Merge pull request #22`）
- CI 四 job 全绿：
  - backend 16m04s（含真实 `PostgresContainerIT`）
  - e2e 18m34s
  - frontend 2m19s
  - deploy-scripts 11s

## 部署

- 服务器 `ubuntu@15.134.169.2`，工作副本 `~/paiban`
- 代码：`git pull --ff-only` 后 HEAD 与 `origin/main` 一致
- 备份：`~/backups/paiban/paiban-pre-20261103T010305Z.sql.gz`（133,567 字节，`gzip -t` 通过）
- 构建：后台 `docker compose build backend frontend`，两个镜像均 Built
- 启动：`docker compose ... up -d`，backend/frontend 重建并 healthy
- Flyway：44 → **47**（V45 锁定/版本、V46 方案 token、V47 来源草稿字段）
- Schema 抽检：`draft_lesson.locked`、`timetable_draft.version`、`draft_cycle_plan_token`、`scheduling_job.source_draft_id` 均存在
- 容器：postgres / backend / frontend / nginx **4 个全 healthy**
- 健康检查：本机 HTTPS 200、公网 `https://paiban.xhyzzz.dpdns.org/api/health` 200
- 新路由：`PUT .../lessons/locks`、`POST .../cycle-plans/search` 返回 401（已注册且要求鉴权，非 404）
- 生产数据未变：drafts 3、draft_lesson 1638、published entries 1092、assignments 448；backend 启动日志无 error/exception

## 回滚

- 代码：`cd ~/paiban && git reset --hard <上一个已知良好 commit>`，重跑构建与 `up -d`；不要加 `--build` 重建回滚镜像。
- 数据库：V45–V47 为加列/加表/加索引；回滚代码前先看 `flyway_schema_history` 与备份。V45 的唯一索引 `uk_draft_lesson_identity` 在部署前 preflight 已确认生产无重复行。
- 备份文件保留在 `~/backups/paiban/`。

## 未执行

- 未改生产课表、未触发自动排课、未发布新草稿。
- 未做历史数据转换；本批只新增能力与 schema。
