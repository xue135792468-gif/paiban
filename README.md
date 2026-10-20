# 排课系统（Paiban）

面向国内单一中学的排课与调代课系统。后端 Spring Boot 3 + Timefold Solver，前端 Vue 3 + Vite + TypeScript，数据库 PostgreSQL 16（本地演示可用内嵌 H2）。

## 功能范围（一期）

- 基础数据：年级、行政班、教师、教室、课程，支持 Excel 批量导入（先预览、后入库）
- 学期与作息表
- 授课任务录入与 Excel 导入
- 约束配置（预置约束库、权重、模板）
- 自动排课（Timefold，异步任务 + 进度轮询）
- 课表草稿拖拽微调与实时冲突校验
- 课表发布、班级/教师/教室/全校四个视角
- 调代课申请、审批（审批 / 直接生效两种模式）、叠加视图与历史追溯
- 系统内通知与钉钉通知（钉钉可选，关闭后系统完整可用）
- 课表导出 Excel / PDF
- 设置中心与初始化向导

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
cd frontend && npm run test:e2e    # Playwright 验收场景
```

> 说明：规格要求集成测试使用 Testcontainers + 真实 PostgreSQL。本仓库保留了 Testcontainers 依赖与
> `PostgresContainerSupport`，在检测到 Docker 时可通过 `-Dtestcontainers=true` 启用；
> 默认执行环境没有 Docker，因此测试使用 H2 的 PostgreSQL 兼容模式。SQL 迁移脚本保持两库通用。

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

## 术语

见 [GLOSSARY.md](./GLOSSARY.md)。代码与 UI 统一使用：行政班、教学班、课节、课表、排课、授课任务、
约束、冲突、手工微调、调代课。
