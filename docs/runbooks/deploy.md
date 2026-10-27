# 部署跑册 · 排课系统（paiban）

服务器 `ubuntu@15.134.169.2`，工作副本 `~/paiban`（`docker-compose.prod.yml` + 服务器本地 `docker-compose.override.yml`，后者不入 git）。
Docker 一律 `sudo -n`。代码变更才需要重建；纯文档变更只要 `up -d`（或什么都不做）。

**该机 sshd 对短时间连续连接限流**：前台跑 `docker compose build` 会被 `Connection closed by remote host` 打断（没并发也一样）。
所以：**长命令一律 `setsid nohup ... > 日志 2>&1 &` 后台跑再轮询**，远程步骤尽量合并进单次 SSH。

**部署前本地门禁**：`bash scripts/verify.sh --deploy`。它要求 Docker 真跑 `PostgresContainerIT`，没有 Docker 会直接失败；不要用 `--full` 跳过真实 PostgreSQL 验证来冒充发布门禁。

0. 进服务器：`ssh -i C:/Users/liu/.ssh/LightsailDefaultKey-ap-southeast-2.pem ubuntu@15.134.169.2`
1. **备份**（失败就停，别往下走）：
   `sudo -n docker exec paiban-postgres-1 pg_dump -U paiban paiban | gzip > ~/backups/paiban/paiban-pre-$(date -u +%Y%m%dT%H%M%SZ).sql.gz`
   判定：文件非空且 `gzip -t` 通过。
2. **同步代码**：`cd ~/paiban && git pull --ff-only`。判定：`git log --oneline -1` 与 `origin/main` 一致；**不要用写死的 commit 哈希做验收**。
3. **构建（必须后台）**：
   `sudo -n sh -c 'cd /home/ubuntu/paiban && setsid nohup docker compose -f docker-compose.prod.yml -f docker-compose.override.yml build backend frontend > /home/ubuntu/ops/paiban-build.log 2>&1 &'`
   轮询（日志属 root，读取要 `sudo -n`）：`sudo -n tail -n 20 /home/ubuntu/ops/paiban-build.log`
   判定：出现 `Image paiban-backend Built` 与 `Image paiban-frontend Built`；超过 10 分钟没有则视为失败，看日志尾部原因。
4. **起容器**：`sudo -n sh -c 'cd /home/ubuntu/paiban && docker compose -f docker-compose.prod.yml -f docker-compose.override.yml up -d'`
   这条会等到健康检查通过（一两分钟）；若中途断连，不要重跑 build，重新连上直接轮询第 5 步的状态。
5. **校验（全部满足才算部署完成）**：
   - Flyway：`sudo -n docker exec paiban-postgres-1 psql -U paiban -d paiban -tAc "select max(version::int) from flyway_schema_history"` ＝ `backend/src/main/resources/db/migration` 里的最高版本；
   - 容器：`sudo -n docker ps --filter name=paiban --format '{{.Names}} {{.Status}}'` ＝ postgres/backend/frontend/nginx **4 个全 healthy**；
   - 本机 HTTPS：`curl -kfsS -o /dev/null -w '%{http_code}' https://127.0.0.1:18443/api/health` ＝ `200`（别用 `http://127.0.0.1:18080`，会被 301 到 HTTPS）；
   - 公网：`curl -fsS -o /dev/null -w '%{http_code}' https://paiban.xhyzzz.dpdns.org/api/health` ＝ `200`。
6. 失败回退：`cd ~/paiban && git reset --hard <上一个已知良好的 commit>`（**不要加 `--build` 去重建回滚镜像**），重跑第 3–5 步；数据库迁移不会自动回滚，先看 `flyway_schema_history` 和第 1 步的备份再决定是否手工恢复。

一键：`bash ~/ops/paiban-deploy.sh`（做第 2–4 步并打印 Flyway 版本与容器状态；备份仍需手工做第 1 步）。

## 导入冒烟配方（文本导入）
导课表必须带 `?target=timetable`（不传 target 会被 400 拒绝：默认值 `workbook` 表示「未指定」，文本管道不猜形状）；
AI 可能把节次写成「上午第1节」，与作息 label（「第1节」）严格匹配会失败，先 `PUT /api/ai/import/sessions/{id}/rows` 改回作息 label 再提交；
入库后 room-fill → publish → SWAP（调课申请必须带 `targetPeriodId`，否则 400「调课必须选择目标节次」）。
