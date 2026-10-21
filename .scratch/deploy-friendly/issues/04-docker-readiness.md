# 04: Docker 就绪探针与依赖等待

**What to build:** 优化 Docker 配置：后端添加健康检查、docker-compose 添加依赖等待、PostgreSQL 添加健康检查。完成后，`docker compose up -d` 启动时，后端会等待 PostgreSQL 就绪后才启动，避免"数据库未就绪导致应用 CrashLoopBackOff"。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `backend/Dockerfile` 添加健康检查：
  ```dockerfile
  HEALTHCHECK --interval=5s --timeout=3s --retries=12 \
    CMD curl -f http://localhost:8080/api/health || exit 1
  ```
- [x] `docker-compose.yml` 添加依赖等待：
  ```yaml
  backend:
    depends_on:
      postgres:
        condition: service_healthy
  ```
- [x] `docker-compose.yml` 为 PostgreSQL 添加健康检查：
  ```yaml
  postgres:
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 12
  ```
- [x] 前端 nginx 添加健康检查（如果前端也容器化）
- [ ] 验证：在干净环境执行 `docker compose up -d`，观察后端是否在 PostgreSQL 就绪后才启动
- [ ] 测试：模拟 PostgreSQL 启动缓慢（如添加 `sleep 30`），验证后端不会崩溃而是等待

> 实现备注：Dockerfile 健康检查额外加了 `--start-period=60s`，避免 Spring Boot 启动期间被误判 unhealthy。`scripts/tests/test-docker-config.sh` 用 PyYAML 静态校验 compose 拓扑与 Dockerfile；最后两项需要真实 Docker，当前环境无法执行，保持未勾选。
