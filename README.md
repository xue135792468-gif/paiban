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
- 默认账号：`admin` / `admin123`
- 首次使用请访问前端地址，按初始化向导操作

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--quiet` / `--no-prompt` | 非交互模式：不打印进度、不暂停，失败时直接返回非零退出码，适合 AI Agent / CI |
| `--admin-password=密码` | 指定初始管理员密码（默认 `admin123`） |
| `--timeout=秒数` | 健康检查最长等待时间，默认 60 秒 |
| `--no-build` | 不重新构建镜像，直接启动已有镜像 |
| `--env-only` | 只生成/检查 `.env`，不启动服务（排障用） |

退出码：`0` 成功；`1` 启动失败；`2` 环境或前置条件不满足（例如未安装 Docker、Docker 未启动）。

启动失败时：

1. 先看后端日志：`docker compose logs backend`
2. 再运行自检：`doctor.bat`（Windows）或 `./doctor.sh`（Linux / macOS）
3. 首次构建需要下载依赖，可能较慢；可以加大等待时间，例如 `--timeout=180`

`.env` 中的密钥只在缺失或仍是占位值时生成，重复运行不会覆盖已有值；`.env` 已加入 `.gitignore`。

> 仓库里的 `启动排课系统.cmd` 是本地开发快捷方式，要求宿主机安装 JDK / Node；日常演示优先用上面的
> 一键启动。

## 功能范围（一期）

- 基础数据：年级、行政班、教师、教室、课程，支持 Excel 批量导入（先预览、后入库）
- 学期与作息表；新学期可从旧学期复制作息、年级、班级与授课任务
- 授课任务录入与 Excel 导入
- 约束配置（24 条预置约束、权重、模板）
- 自动排课（Timefold，异步任务 + 进度轮询 + 取消）
- 课表草稿拖拽微调与实时冲突校验
- 课表发布、班级/教师/教室/全校四个视角
- 调代课申请、审批（审批 / 直接生效两种模式）、叠加视图与历史追溯
- 系统内通知与钉钉通知（钉钉可选，关闭后系统完整可用；含钉钉 H5 免登）
- 课表导出 Excel / PDF
- 设置中心、初始化向导、用户账号 Excel 批量创建

## 功能范围（二期）

- 初中 / 高中模式切换：默认初中，隐藏权重滑块、约束模板、多草稿对比、全校视角等高级功能
- AI 供应商与模型管理：预设 DeepSeek / Gemini / 通义千问 / 智谱，支持自定义 OpenAI 兼容端点；API Key 加密存储
- AI 数据导入：Excel/CSV、文本粘贴、图片三种输入，AI 解析 → 预览（低置信度标黄）→ 人工微调 → 入库
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

### 生产部署清单

上线前逐项确认：

1. **密钥与数据库密码**：`.env` 里的 `PAIBAN_JWT_SECRET`（至少 32 字符）、
   `PAIBAN_AI_ENCRYPTION_KEY`、`POSTGRES_PASSWORD` 全部换成强随机值；
   `openssl rand -hex 32` 可直接生成。跨域访问时再设置
   `PAIBAN_CORS_ALLOWED_ORIGINS`（同源 nginx 部署不需要）。
2. **HTTPS 与域名**：飞书 H5 要求 HTTPS。用 Caddy / Traefik / nginx + Let's Encrypt
   或 Cloudflare Tunnel 在 443 终止 TLS，转发到 `127.0.0.1:5173`。
3. **端口**：compose 已把 PostgreSQL（`127.0.0.1:5432`）和后端（`127.0.0.1:8080`）限制在本机；
   防火墙只对公网开放 80/443。
4. **首次启动**：`docker compose up -d --build`，确认
   `curl http://127.0.0.1:8080/api/health` 返回 `UP`。
5. **初始管理员**：若未设置 `PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD`，从
   `docker compose logs backend` 读取一次性生成的密码；登录后立即修改。
6. **系统设置**：设置中心把「外部访问地址」填成 `https://你的域名`；
   启用飞书时填写 App ID / App Secret / 验证 Token，并把事件回调地址设为
   `https://你的域名/api/integrations/feishu/event`。
7. **备份**：`./scripts/backup.sh` 生成 PostgreSQL 与 uploads 备份，默认保留 14 天；
   恢复命令见脚本头部注释。建议加到 cron。
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
```

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
- **测试替身**：`paiban.integration.dingtalk.fake=true`（e2e/test profile）启用确定性的假钉钉客户端。

## 已知限制

- **钉钉 Stream 长连接**：一期未内嵌官方 Stream SDK。增量事件通过
  `POST /api/integrations/dingtalk/event`（需 `X-DingTalk-Token`，值为设置中心的“事件令牌”）接收，
  生产环境可用官方 SDK 将事件转发到该端点；全量同步走 `POST /api/integrations/dingtalk/sync`。
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
| `paiban.ai.encryption-key` | AI 供应商 API Key 的 AES-256 加密密钥（环境变量 `PAIBAN_AI_ENCRYPTION_KEY`） |
| `paiban.ai.features.*` | AI 特性开关；二期已提供供应商管理、数据导入、调代推荐与操作历史 |
| `school.type`（设置中心） | `junior` / `senior`，默认 `junior`，仅影响 UI 显示 |
| `ai.import.confidence-threshold`（设置中心） | AI 导入低置信度阈值，默认 `0.8` |

> 生产环境必须设置 `PAIBAN_JWT_SECRET`（至少 32 字符）。在非 `local`/`test`/`e2e` profile 下，
> 若仍使用仓库内置的默认密钥，应用会拒绝启动。
>
> 生产环境还必须设置 `PAIBAN_AI_ENCRYPTION_KEY`（足够长的随机字符串）。未设置时会回退到仓库内的
> 开发默认值，等同于公开密钥；`application.yml` 的默认值只用于本地开发。

## 术语

见 [GLOSSARY.md](./GLOSSARY.md)。代码与 UI 统一使用：行政班、教学班、课节、课表、排课、授课任务、
约束、冲突、手工微调、调代课。
