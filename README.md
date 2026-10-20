# 排课系统（Paiban）

面向国内单一中学的排课与调代课系统。后端 Spring Boot 3 + Timefold Solver，前端 Vue 3 + Vite + TypeScript，数据库 PostgreSQL 16（本地演示可用内嵌 H2）。

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

默认管理员账号：`admin` / `admin123`。

### 使用 PostgreSQL 运行

```bash
docker compose up -d postgres
cd backend
SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
```

或一键：`docker compose up --build`，前端在 http://localhost:5173 ，后端在 http://localhost:8080 。

## 测试

```bash
cd backend && ./mvnw verify        # 单元 + 集成测试（默认 H2，PostgreSQL 兼容模式）
cd frontend && npm run lint && npm run build

# E2E：Playwright 会自动启动后端（e2e profile）与前端
cd frontend && npx playwright install chromium
cd frontend && npm run test:e2e
```

E2E 需要 `JAVA_HOME` 指向 JDK 21（Windows 下请使用 `E:\...` 形式的路径，Playwright 通过 `mvnw.cmd` 启动后端）。

> 说明：规格要求集成测试使用 Testcontainers + 真实 PostgreSQL。`PostgresContainerIT` 已按此实现，
> 但只在检测到 Docker 时执行：`./mvnw verify -Dtestcontainers=true`。默认执行环境没有 Docker，
> 因此常规测试使用 H2 的 PostgreSQL 兼容模式；所有迁移脚本保持两库通用。

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
  `POST /api/integrations/dingtalk/event`（需 `X-DingTalk-Token`，值为 AppSecret）接收，
  生产环境可用官方 SDK 将事件转发到该端点；全量同步走 `POST /api/integrations/dingtalk/sync`。
- **PDF 导出**：用 OpenPDF `PdfPTable` 直接绘制，而不是 HTMLWorker；CJK 字体从系统字体或
  `paiban.export.pdf-font-path` 加载，未找到时退化为 Helvetica。
- **约束设置快照**：`ConstraintSettingsHolder` 为进程级静态变量，依赖“同一时刻只运行一个求解任务”的
  约束（`AsyncConfig` 单线程 + 数据库状态检查）。若未来放开并发求解，需要改为按 solution 携带设置。
- **学期回看**：课表查询接口目前固定当前学期；历史学期数据已隔离存储，接口预留了扩展空间。
- **测试**：默认使用 H2（PostgreSQL 兼容模式）；Testcontainers 版本通过
  `./mvnw verify -Dtestcontainers=true` 在具备 Docker 的环境执行。

## 目录结构

```
backend/   Spring Boot 单体应用（7 个业务模块：foundation / scheduling / timetable /
           substitution / identity / integration / notification）
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
| `paiban.ai.features.*` | AI 特性开关（一期占位，默认关闭） |
| `paiban.scheduling.max-concurrent-jobs` | 同时运行的排课任务数 |

> 生产环境必须设置 `PAIBAN_JWT_SECRET`（至少 32 字符）。在非 `local`/`test`/`e2e` profile 下，
> 若仍使用仓库内置的默认密钥，应用会拒绝启动。

## 术语

见 [GLOSSARY.md](./GLOSSARY.md)。代码与 UI 统一使用：行政班、教学班、课节、课表、排课、授课任务、
约束、冲突、手工微调、调代课。
