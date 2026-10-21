# 排课系统（Paiban）

面向国内单一中学的排课与调代课系统。后端 Spring Boot 3 + Timefold Solver，前端 Vue 3 + Vite + TypeScript，数据库 PostgreSQL 16（本地演示可用内嵌 H2）。

## 一键启动（推荐给非技术用户）

前提只有一个：安装 Docker Desktop（Windows / macOS）或 Docker Engine（Linux）。
**宿主机不需要安装 Java、Node 或 Maven**，所有依赖都在容器里构建和运行。

- Windows：双击仓库根目录的 `start.bat`
- Linux / macOS：在仓库根目录执行 `./start.sh`

脚本会自动完成这些事：检测 Docker 是否安装并运行 → 检查内存与 8080 / 5173 / 5432 端口 →
生成 `.env` 密钥 → 构建并启动数据库、后端、前端 → 等待后端健康检查通过 →
打印访问地址和默认账号。

启动成功后：

- 前端地址：http://localhost:5173
- 后端地址：http://localhost:8080
- 默认账号：`admin` / `admin123`（首次启动时可用 `--admin-password=` 指定；账号创建后再传该参数不会改密码）
- 首次使用请访问前端地址，按初始化向导操作

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--quiet` / `--no-prompt` | 非交互模式：不打印进度、不暂停，失败时直接返回非零退出码，适合 AI Agent / CI |
| `--admin-password=密码` | 指定初始管理员密码（默认 `admin123`） |
| `--timeout=秒数` | 健康检查最长等待时间，默认 60 秒 |
| `--no-build` | 不重新构建镜像，直接启动已有镜像 |
| `--env-only` | 只生成/检查 `.env`，不启动服务（排障用） |
| `--prod` | 使用生产栈 `docker-compose.prod.yml`：nginx 终止 TLS，只发布 80/443 |

### 生产栈一键启动（`--prod`）

```bash
./start.sh --prod                       # Linux / macOS
start.bat --prod                        # Windows
./start.sh --prod --env-only            # 只准备 .env，不碰 Docker / 证书
```

`--prod` 会：

1. 照常引导 `.env`（占位密钥会被替换；`POSTGRES_PASSWORD` 同样自动补全，因此
   `${POSTGRES_PASSWORD:?...}` 不会再阻塞启动）；
2. 若 `deploy/certs/fullchain.pem` / `privkey.pem` 缺失，用本机 `openssl`（没有则用
   `alpine/openssl` 容器）生成一对 **CN=localhost、365 天**的自签证书，并打印醒目警告；
3. 用 `docker compose -f docker-compose.prod.yml up -d --build` 启动；
4. 用 `curl -kfsS https://127.0.0.1/api/health` 等待就绪，最后打印
   `前端地址: https://localhost`（HTTP 会 301 跳转到 HTTPS）。

> 自签证书**仅供本机测试**。正式上线前必须替换为受信任证书（Let's Encrypt / Caddy / 商业证书），
> 否则浏览器会告警，飞书等客户端会直接拒绝连接。

退出码：`0` 成功；`1` 启动失败；`2` 环境或前置条件不满足（例如未安装 Docker、Docker 未启动）。

启动失败时：

1. 先看后端日志：`docker compose logs backend`
2. 再运行自检：`doctor.bat`（Windows）或 `./doctor.sh`（Linux / macOS）
3. 首次构建需要下载依赖，可能较慢；可以加大等待时间，例如 `--timeout=180`

`.env` 中的密钥只在缺失或仍是占位值时生成，重复运行不会覆盖已有值；`.env` 已加入 `.gitignore`。

> 仓库里的 `启动排课系统.cmd` 是本地开发快捷方式，要求宿主机安装 JDK / Node；日常演示优先用上面的
> 一键启动。

图文安装教程与常见问题：[docs/installation-guide.md](docs/installation-guide.md)。

## 功能范围（一期）

- 基础数据：年级、行政班、教师、教室、课程，支持 Excel 批量导入（先预览、后入库）
- 学期与作息表；新学期可从旧学期复制作息、年级、班级与授课任务
- 授课任务录入与 Excel 导入
- 约束配置（24 条预置约束、权重、模板）
- 自动排课（Timefold，异步任务 + 进度轮询 + 取消）
- 课表草稿拖拽微调与实时冲突校验
- 手工调课：同班换位、跨班换人，相关老师筛选、双课表预览与撤销
- 课表发布、班级/教师/教室/全校四个视角
- 调代课申请、审批（审批 / 直接生效两种模式）、叠加视图与历史追溯
- 系统内通知与钉钉通知（钉钉可选，关闭后系统完整可用；含钉钉 H5 免登）
- 课表导出 Excel / PDF
- 设置中心、初始化向导、用户账号 Excel 批量创建

## 功能范围（二期）

- 初中 / 高中模式切换：默认初中，隐藏权重滑块、约束模板、多草稿对比、全校视角等高级功能
- AI 供应商与模型管理：预设 DeepSeek / Gemini / 通义千问 / 智谱，支持自定义 OpenAI 兼容端点；API Key 加密存储
- AI 数据导入：Excel/CSV、文本粘贴、图片三种输入，AI 解析 → 预览（低置信度标黄）→ 人工微调 → 入库
- 通用 AI 导入：教师与课表，支持原始表格网格、目标识别、名称映射与草稿生成
- AI 调代推荐：审批界面手动触发，返回 3 名候选教师及理由，不自动创建或批准申请
- AI 操作历史：导入与调代调用统一审计，支持筛选、详情与 Excel/PDF 导出

## 本地启动（三条命令）

```bash
# 1. 启动数据库（也可跳过，默认 profile 使用内嵌 H2）
docker compose up -d postgres

# 2. 启动后端（默认 local profile，使用 ./data/paiban 的 H2 文件库）
cd backend && ./mvnw spring-boot:run

# 3. 启动前端
cd frontend && npm install && npm run dev
```

打开 http://localhost:5173 ，首页会显示后端 `/api/health` 的返回结果。

Windows 下也可以直接双击根目录的 `启动排课系统.cmd`：自动启动前后端、等后端就绪后打开浏览器；
`停止排课系统.cmd` 负责停止。日志在 `.tools/logs/`。

默认管理员账号：`admin` / `admin123`。

### 使用 PostgreSQL 运行

```bash
docker compose up -d postgres
cd backend
SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
```

或一键：先复制 `.env.example` 为 `.env` 并改掉所有占位值，再
`docker compose up -d --build`，前端在 http://localhost:5173 ，后端在 http://localhost:8080 。

compose 会先等 PostgreSQL 通过 `pg_isready` 健康检查再启动后端，前端同样等后端健康后才启动；
后端健康检查打 `/api/health`，也就是整套服务的就绪信号。`backend/Dockerfile` 留了 60s
`start-period` 给 Spring Boot 启动，这些配置可用 `scripts/tests/test-docker-config.sh` 静态校验。

### 生产环境部署（docker-compose.prod.yml）

`docker-compose.prod.yml` 是独立的生产栈：PostgreSQL、后端、前端都只在内部网络暴露，
只有 nginx 发布 80 / 443，TLS 在 nginx 终止，日志写到 `./logs/nginx`。
`chore/prod-hardening` 已合入主线，本文件与 nginx / 备份脚本即其最终形态，无需再合并分支。

**1. 准备证书**：把 `fullchain.pem` 与 `privkey.pem` 放进 `deploy/certs/`（目录已存在，证书被 gitignore）。
本地/测试可先自签：

```bash
mkdir -p deploy/certs
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout deploy/certs/privkey.pem \
  -out deploy/certs/fullchain.pem \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

正式环境用 Let's Encrypt（certbot）签发后把两个文件复制到 `deploy/certs/`，或在前面再加一层
Caddy / Cloudflare Tunnel 管理证书。证书缺失时 nginx 容器会启动失败，这是预期的快速失败。

**2. 配置 `.env`**：`cp .env.example .env`，然后：

- `POSTGRES_PASSWORD` **必填**：生产 compose 用 `${POSTGRES_PASSWORD:?...}` 快速失败，
  避免用默认数据库密码上线；可用 `openssl rand -hex 32` 生成。
  用 `./start.sh --prod` 启动时，占位值会被自动替换成随机密码，因此不会卡在这里。
- `PAIBAN_JWT_SECRET`、`PAIBAN_AI_ENCRYPTION_KEY` 可以留空：后端 entrypoint 首次启动时
  自动生成并写入 `./data/app/secrets.env`，重启复用、不会覆盖显式提供的值。
  `.env.example` 里的 `replace-with...` 占位值同样被视为未设置。
- `PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD` 留空（或仍是 `.env.example` 的占位值）时同样
  自动生成，首次登录密码见后端日志；占位值不会被当成真实密码。
- 同源部署（nginx 同时代理前端与 `/api`）不需要设置 `PAIBAN_CORS_ALLOWED_ORIGINS`。

**3. 启动**：

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

**4. 验证健康**（自签证书需要 `-k`）：

```bash
curl -k https://127.0.0.1/healthz       # ok
curl -k https://127.0.0.1/api/health    # {"status":"UP",...}
curl -I http://127.0.0.1/               # 301 跳转到 https
```

**5. 备份与恢复**（生产 compose 用 `COMPOSE_FILE` 或 `--compose-file` 指定）：

```bash
COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh
COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh          # 只打印计划，退出码 2
COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh --yes    # 真正执行
```

`restore.sh` 默认选 `backups/` 里最新的 `paiban-*.dump`，校验文件非空后才停止
backend / frontend，再执行 `pg_restore --clean --if-exists`，并在存在
`backups/uploads-<stamp>.tar.gz` 时一并恢复上传文件。两个脚本都支持 `--help`。
定时备份示例（cron，每天 3 点）：

```cron
0 3 * * * cd /opt/paiban && COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh >> /var/log/paiban-backup.log 2>&1
```

生产栈的静态校验：`scripts/tests/test-prod-config.sh`（无需 Docker）。

#### 上线前安全检查清单

上线前逐项确认：

1. **密钥与数据库密码**：`.env` 里的 `PAIBAN_JWT_SECRET`（至少 32 字符）、
   `PAIBAN_AI_ENCRYPTION_KEY`、`POSTGRES_PASSWORD` 全部换成强随机值；
   `openssl rand -hex 32` 可直接生成。跨域访问时再设置
   `PAIBAN_CORS_ALLOWED_ORIGINS`（同源 nginx 部署不需要）。
2. **HTTPS 与域名**：飞书 H5 要求 HTTPS。生产 compose 已内置 nginx 在 443 终止 TLS；
   证书放在 `deploy/certs/`，或改用 Caddy / Cloudflare Tunnel 在前置层终止。
3. **端口**：生产 compose 只发布 80/443；PostgreSQL、后端、前端都只在内部网络，
   防火墙只对公网开放 80/443。
4. **首次启动**：`docker compose -f docker-compose.prod.yml up -d --build`，确认
   `curl -k https://127.0.0.1/api/health` 返回 `UP`。
5. **初始管理员**：若未设置 `PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD`，从
   `docker compose -f docker-compose.prod.yml logs backend` 读取一次性生成的密码；登录后立即修改。
6. **系统设置**：设置中心把「外部访问地址」填成 `https://你的域名`；
   启用飞书时填写 App ID / App Secret / 验证 Token，并把事件回调地址设为
   `https://你的域名/api/integrations/feishu/event`。
7. **备份**：`COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh` 生成 PostgreSQL 与
   uploads 备份，默认保留 14 天；恢复用 `scripts/restore.sh`。建议加到 cron。
8. **限流**：应用本身没有登录失败锁定，请在反向代理层加登录限流。

> 生产环境不要使用默认的 `admin/admin123`；非 local/test/e2e profile 下应用会拒绝默认 JWT 密钥。

### 首次 AI 部署向导

```bash
./scripts/setup-ai-prod.sh
```

向导会生成两个本地密钥、打开 DeepSeek / Gemini 控制台收集 API Key、启动 compose 栈、创建并测试两个
供应商、把「数据导入」分配给 Gemini（视觉）、「调代推荐」分配给 DeepSeek，最后跑一次真实文本导入冒烟。
密钥只写入 `.env`（已 gitignore），不会进入仓库。

## 测试

```bash
scripts/verify.sh          # 后端 verify + 前端 lint/build
scripts/verify.sh --full   # 追加 Playwright 验收场景
scripts/verify-deploy.sh   # 部署脚本测试（无需 Docker；CI 的 deploy-scripts job）
```

`scripts/verify-deploy.sh` 依次运行 `scripts/tests/test-*.sh`（compose 拓扑、doctor 退出码、
密钥自愈、一键启动、生产配置与备份脚本），打印每个套件名并在第一个失败处停止；
本机没有 PowerShell 时自动跳过 PowerShell 相关断言。CI 中由 `deploy-scripts` job 执行。

`scripts/verify.sh` 与 CI（`.github/workflows/ci.yml`）走同一条路径：检测到 Docker 时自动带
`-Dtestcontainers=true`，CI 里一定带。需要单独跑某一步时：

```bash
cd backend && ./mvnw verify        # 单元 + 集成测试（默认 H2，PostgreSQL 兼容模式）
cd frontend && npm run lint && npm run build
cd frontend && npm run test:e2e    # Playwright 会自动启动后端（e2e profile）与前端
```

E2E 需要 JDK 21。Playwright 配置会优先使用仓库内的 `.tools/jdk-*`（存在时自动设置 `JAVA_HOME`）；
`scripts/verify.sh` 也会自动 source `.tools/env.sh`。CI 使用 runner 自带的 JDK。

> 说明：规格要求集成测试使用 Testcontainers + 真实 PostgreSQL。`PostgresContainerIT` 已按此实现，
> 但只在检测到 Docker 时执行：`./mvnw verify -Dtestcontainers=true`。默认执行环境没有 Docker，
> 因此常规测试使用 H2 的 PostgreSQL 兼容模式；所有迁移脚本保持两库通用。

### 手动验收（飞书沙箱）

飞书沙箱验收依赖真实租户与 H5 容器，CI 不执行；发版前在测试企业内逐项确认：

1. **启动同步**：飞书启用且为当前平台时，启动日志出现「启动时飞书组织同步完成」，教师按
   `feishuUserId` → `feishuOpenId` → 工号导入/更新；把 App Secret 改错后重启，只记录告警与
   `FEISHU_SYNC` 失败审计，应用仍能启动。
2. **免登与绑定**：从飞书工作台打开 H5 应用应自动免登；未绑定时跳转 `/bind`，用「姓名 + 工号/手机号」
   完成绑定后进入首页。已绑定其他教师/其他飞书账号时提示 409 文案。
3. **平台切换**：设置页「IM 平台」从钉钉切到飞书，确认弹窗文案、切换后教师/班级/课表数据仍在，
   `PLATFORM_SWITCH` 审计包含 from → to，目标平台目录同步被触发。
4. **调代卡片**：审批通过后，原教师/代课教师飞书收到交互卡片（标题、正文、「查看详情」按钮）；
   断开网络或教师未绑定时降级为系统内通知，`notification_log.platform` 分别记为 `feishu` / `system`。
5. **事件回调**：`challenge` 原样返回；错误 `X-Feishu-Token` 返回 403；加密事件返回 400；
   `contact.user.created_v3` / `updated_v3` / `deleted_v3` 分别新增、更新、停用教师（不删除）。


### 性能基准（ADR-0017）

`PerformanceBenchmarkIT` 按 ADR-0017 的学校规模生成数据并测量全部指标。默认跳过，手动执行：

```bash
cd backend && ./mvnw -Dperf=true -Dtest=PerformanceBenchmarkIT test
```

本机实测（2026-10-20，Windows，H2 PostgreSQL 兼容模式，100 班 / 500 教师 / 200 教室 / 2400 节课）：

| 指标 | 目标 | 实测 |
|---|---|---|
| 排课到零硬冲突 | < 5 分钟 | 86 秒 |
| 拖拽冲突校验 | < 500ms | 30ms |
| 班级课表 | < 200ms | 19ms |
| 教师课表 | < 200ms | 25ms |
| 教室课表 | < 200ms | 25ms |
| 全校总表 | < 2s | 220ms |
| 50 并发 × 5 次班级课表 | 全部成功 | 1.6 秒 |

数值随机器变化；生产环境切换到 PostgreSQL 后建议重跑一次再对外承诺。

## 实现说明

- **排课引擎**：24 条预置约束全部用 Timefold Constraint Streams 实现；约束配置（启用、硬/软、权重、参数）
  在每次求解前生成不可变快照，支持不同学期使用不同规则。同一学期同一时刻只允许一个求解任务。
- **增量校验**：手工微调通过 `DraftValidator` 直接检查硬约束并返回人类可读原因
  （"张老师此时在 3 班有数学课"），毫秒级返回；`GET /api/timetable-drafts/{id}/validation` 返回整份草稿的冲突。
- **课表合并视图**：`timetable_entry` 基线只读，调代以 `substitution` 追加事件叠加；`view=baseline`
  返回原始基线，`view=latest&date=YYYY-MM-DD` 叠加该日生效的调代。
- **PDF 导出**：使用 OpenPDF 直接绘制 A4 横向表格（PdfPTable），并自动查找系统中文字体
  （`paiban.export.pdf-font-path` 可指定）。未使用 HTMLWorker，以避免 CJK 字体解析问题。
- **钉钉集成**：`AuthProvider` / `OrgDataProvider` / `NotificationChannel` 三组接口均有本地与钉钉实现；
  钉钉 Stream 增量事件通过 `POST /api/integrations/dingtalk/event` 接收（生产可接官方 Stream SDK 转发）。
  关闭集成后系统完整可用，通知自动降级为系统内消息。
- **飞书集成**：与钉钉同一组接口的飞书实现；设置中心「飞书集成」卡片可配置 App ID / App Secret /
  验证 Token 与同步策略（`feishu` 飞书为准、`local` 本地为准），并手动触发全量同步
  （`POST /api/integrations/feishu/sync`）与查看 `FEISHU_SYNC` 同步日志。
  飞书启用且为当前平台时，应用启动后会执行一次全量同步（`paiban.integration.feishu.sync-on-startup`，
  默认 `true`；test / e2e profile 关闭）。拉取失败只写 `FEISHU_SYNC` 失败审计并告警，不会把
  「拉取失败」误判为「全员离职」，也不阻塞启动。
  飞书增量事件通过 `POST /api/integrations/feishu/event` 接收，校验头为 `X-Feishu-Token`
  （值为设置中心的「验证 Token」）；同时兼容 v2.0 事件信封与扁平 v1 格式。
  未启用或 Token 不匹配时分别返回 400 / 403，未知事件类型按 200 忽略。
  飞书开放平台以 HTTP 200 + 非零 `code` 表示业务失败，客户端会按失败处理并记录数字错误码，
  通讯录拉取失败时抛出结构化失败而不是返回空列表。
- **飞书通知**：调代等通知以飞书交互卡片发送（Markdown 正文 +「查看详情」按钮，按模板着色）；
  飞书不可达或教师未绑定飞书账号时自动降级为系统内通知。通知记录用 `platform` 标记来源
  （`feishu` / `dingtalk` / `system`）。复用现有通知接口，不新增端点。
  升级到本版本时，V19 迁移按 `channel` 回填历史 `notification_log.platform`
  （`dingtalk` → `dingtalk`，`local`/`system` → `system`，其余取 `channel` 原值）。
- **IM 平台切换**：设置中心「IM 平台」卡片用单选按钮选择「不启用 / 钉钉 / 飞书」，
  **当前平台只能通过 `PUT /api/integrations/platform` 修改**；`PUT /api/settings` 不再接受
  `integration.active-platform`（返回 400）。切换时停用原平台、启用新平台，
  触发新平台的组织目录同步，并写入 `PLATFORM_SWITCH` 审计（含 from → to）。
  设置写入与审计在同一事务内提交，目录同步（网络调用）在事务外执行。
  切换只改平台开关，不触碰教师、班级、课程、课表与调代数据；历史通知记录按 `platform` 保留来源。
  未配置凭证（钉钉 AppKey/AppSecret 或飞书 App ID/App Secret）时切换返回 400。
  通知渠道只在当前平台生效：`FeishuNotificationChannel` 要求飞书已启用且为当前平台，
  `DingTalkNotificationChannel` 同理；免登与组织同步仍各自按启用开关判断。
  `integration.active-platform` 为空时按「钉钉优先、其次飞书、否则不启用」派生，兼容一期部署。
- **飞书自助绑定**：飞书免登找不到本地账号时返回 `binding_required` 与 10 分钟有效的 `bindToken`，
  前端跳转 `/bind?token=...`；教师填写姓名并至少提供工号或手机号，两者都填时必须指向同一教师，
  姓名需与档案完全一致。绑定成功写入 `teacher.feishu_user_id` / `feishu_open_id`、审计 `FEISHU_BIND`
  并签发本地账号会话。飞书账号已绑定其他教师、教师已绑定其他飞书账号、或教师尚未开通系统账号时
  返回 409，由教务管理员在「教师管理」中手动修正。
- **教师平台绑定**：教师列表显示「平台绑定」标签（钉钉 / 飞书 / 两者 / 未绑定）；新建或编辑教师时
  可直接填写钉钉 UserID、飞书 UserID 与飞书 OpenID，留空表示清除该绑定。
- **测试替身**：`paiban.integration.dingtalk.fake=true`（e2e/test profile）启用确定性的假钉钉客户端。

## 已知限制

- **钉钉 Stream 长连接**：一期未内嵌官方 Stream SDK。增量事件通过
  `POST /api/integrations/dingtalk/event`（需 `X-DingTalk-Token`，值为设置中心的“事件令牌”）接收，
  生产环境可用官方 SDK 将事件转发到该端点；全量同步走 `POST /api/integrations/dingtalk/sync`。
- **飞书 Stream 长连接**：本仓库未内嵌官方飞书 Stream SDK，也不自行维护 WebSocket 长连接。
  增量事件由 `POST /api/integrations/feishu/event` 接收（需 `X-Feishu-Token`，值为设置中心的“验证 Token”），
  生产环境可用官方 Stream SDK 订阅 `contact.user.created_v3` / `updated_v3` / `deleted_v3`
  并转发到该端点；事件加密须在飞书开放平台关闭，否则接口返回 400。
  全量同步走 `POST /api/integrations/feishu/sync`；删除事件只把教师标记为停用，不删除本地记录。
- **自助绑定范围**：仅飞书提供 `/bind` 自助绑定（姓名 + 工号/手机号）；钉钉没有自助绑定页面，
  绑定关系由管理员在「教师管理」的编辑弹窗中填写/修正。
- **前端测试**：仓库没有前端单元测试 runner；飞书环境检测（`window.tt`）、免登与自助绑定流程
  由 Playwright 验收场景覆盖（`frontend/e2e/feishu-login.spec.ts`、`platform-switch.spec.ts`）。
- **PDF 导出**：用 OpenPDF `PdfPTable` 直接绘制，而不是 HTMLWorker；CJK 字体从系统字体或
  `paiban.export.pdf-font-path` 加载，Docker 镜像已内置 `fonts-noto-cjk`。
- **约束设置快照**：`ConstraintSettingsHolder` 为进程级静态变量，因此一期固定为单任务串行求解
  （`AsyncConfig` 单线程 + 数据库状态检查），不提供 `max-concurrent-jobs` 配置项。若未来放开并发求解，
  需要改为按 solution 携带设置。
- **学期回看**：课表查询接口目前固定当前学期；历史学期数据已隔离存储，接口预留了扩展空间。
- **年级组长**：一期未建模，通知范围 `with-grade-leader` 实际包含班主任；设置页已注明。
- **登录限速**：应用层未内置失败锁定，生产部署建议在反向代理/网关层配置限流。
- **测试**：默认使用 H2（PostgreSQL 兼容模式）；CI 使用 `-Dtestcontainers=true` 在真实 PostgreSQL 上
  跑 Flyway 校验，并执行 6 个 Playwright 验收场景。约束引擎测试覆盖主要硬约束与部分软约束，
  尚未做到 24 条逐一覆盖。

## 目录结构

```
backend/   Spring Boot 单体应用（业务模块：foundation / scheduling / timetable /
           substitution / identity / integration / notification / ai）
frontend/  Vue 3 SPA
docs/adr/  架构决策记录
.scratch/  规格与票据（本地 issue tracker）
```

## 配置项

所有业务配置集中在 `application.yml` 的 `paiban.*` 下，可在设置中心在线修改并立即生效：

| 配置项 | 说明 |
| --- | --- |
| `paiban.substitution.approval-mode` | 调代审批模式：`approval` / `direct` |
| `paiban.notification.scope` | 通知范围：`minimal` / `with-grade-leader` / `with-admin` |
| `paiban.integration.dingtalk.enabled` | 钉钉集成开关 |
| `paiban.integration.feishu.sync-on-startup` | 启动时是否全量拉取飞书通讯录，默认 `true`（仅当飞书启用且为当前平台；test/e2e 为 `false`） |
| `integration.active-platform`（只读） | 当前 IM 平台：`none` / `dingtalk` / `feishu`；只能通过 `PUT /api/integrations/platform` 修改，为空时按启用开关派生（钉钉优先） |
| `paiban.ai.encryption-key` | AI 供应商 API Key 的 AES-256 加密密钥（环境变量 `PAIBAN_AI_ENCRYPTION_KEY`） |
| `paiban.ai.features.*` | AI 特性开关；二期已提供供应商管理、数据导入、调代推荐与操作历史 |
| `school.type`（设置中心） | `junior` / `senior`，默认 `junior`，仅影响 UI 显示 |
| `ai.import.confidence-threshold`（设置中心） | AI 导入低置信度阈值，默认 `0.8` |

### 飞书应用创建与配置

1. **创建应用**：飞书开放平台 → 开发者后台 → 创建「企业自建应用」，在「凭证与基础信息」获取 App ID / App Secret。
2. **H5 微应用主页**：「添加应用能力」启用「网页应用」，桌面端/移动端主页地址填 `https://<你的域名>/login`
   （与系统前端同源，浏览器访问仍走账号密码登录）。
3. **免登权限**：「权限管理」申请用户基本信息与 user id 读取权限（如 `contact:user.base:readonly`、
   `contact:user.employee_id:readonly`），创建版本并发布，由企业管理员审核通过。
4. **安全设置**：「安全设置 → 重定向 URL」填 `https://<你的域名>/login`；若启用组织事件，
   在「事件与回调」把请求地址指向 `https://<你的域名>/api/integrations/feishu/event` 并填写 Verification Token。
5. **启用集成**：设置中心 →「飞书集成」填入 App ID / App Secret 并启用，再到「IM 平台」选择飞书并切换。
   教师从飞书工作台点击应用图标即免登；尚未绑定飞书身份时自动进入 `/bind` 页面，
   填写姓名与工号（或手机号）完成自助绑定。若提示「未找到您的信息」，请联系教务管理员核对档案，
   或在「教师管理」中直接填写飞书 UserID / OpenID。

> 生产环境必须设置 `PAIBAN_JWT_SECRET`（至少 32 字符）。在非 `local`/`test`/`e2e` profile 下，
> 若仍使用仓库内置的默认密钥，应用会拒绝启动。
>
> 生产环境还必须设置 `PAIBAN_AI_ENCRYPTION_KEY`（足够长的随机字符串）。未设置时会回退到仓库内的
> 开发默认值，等同于公开密钥；`application.yml` 的默认值只用于本地开发。

## 术语

见 [GLOSSARY.md](./GLOSSARY.md)。代码与 UI 统一使用：行政班、教学班、课节、课表、排课、授课任务、
约束、冲突、手工微调、调代课。
