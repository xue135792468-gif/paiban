# 01: 项目骨架与本地开发环境

**What to build:** 搭建可运行的最小骨架：Maven + Spring Boot 3 + Java 21 后端、Vue 3 + Vite + TypeScript 前端、PostgreSQL docker-compose、健康检查接口、前端空白首页、本地一键启动脚本、CI 雏形。完成后，开发者克隆仓库执行一条命令即可在本地跑起整套系统，浏览器访问能看到前端页面调用后端 `/api/health` 返回 200。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 后端 Maven 工程可 `./mvnw spring-boot:run` 启动，暴露 `/api/health` 返回 `{ "status": "UP" }`
- [ ] 前端 Vite 工程可 `npm run dev` 启动，首页能调用 `/api/health` 并显示结果
- [ ] 根目录 `docker-compose.yml` 一键起 PostgreSQL 16（含数据卷持久化）
- [ ] 根目录 README 包含"本地启动"章节，三条命令以内跑起来
- [ ] GitHub Actions（或本地等价脚本）能跑 `./mvnw verify` 与 `npm run build` 通过
- [ ] 后端集成 Testcontainers，一个示例集成测试跑通
- [ ] 前端集成 ESLint + Prettier，`npm run lint` 通过
