# 排课系统部署体验优化规格：低智商 Agent 友好

Status: ready-for-agent

---

## Problem Statement

当前系统部署需要多步骤操作（Docker 启动数据库、Maven 启动后端、npm 启动前端），且依赖用户具备技术背景（知道装 JDK、Node、Docker，知道配置数据库连接）。当测试用户通过 AI Agent 安装时，低能力 Agent 容易因以下原因失败：
- 交互式 Prompt 阻塞（Agent 不会响应 stdin）
- 环境变量缺失导致 `${VAR:?err}` 崩溃
- 数据库未就绪导致应用 CrashLoopBackOff
- 宿主机环境污染（要求特定 Java/Node 版本）
- 错误信息不友好（Agent 无法诊断"Connection refused"）

## Solution

基于 Plane / Ghost / Cal.com 的调研结论，实现"低智商 Agent 友好"的部署体验：

1. **一键启动脚本**：`start.bat` / `start.sh`，单入口命令，自动检测环境、启动服务、打印访问地址和默认账号
2. **自检命令**：`doctor.bat` / `doctor.sh`，机器可读的诊断报告（JSON 格式），便于 Agent 精准捕获问题
3. **非交互模式**：所有脚本支持 `--quiet` / `--no-prompt` 参数，静默运行
4. **内部自愈**：数据库自动建表、自动迁移、自动创建 admin 账号
5. **安全默认值**：内部密钥自动生成，避免 `${VAR:?err}` 崩溃
6. **就绪探针**：应用启动前等待数据库就绪
7. **宿主机零污染**：所有依赖在 Docker 容器内闭环，不要求宿主机装 Java/Node/Maven
8. **安装指南**：给非技术用户的图文文档，每步配截图占位

## User Stories

### 一键启动

1. 作为**测试用户（或 AI Agent）**，我希望下载代码后执行 `start.bat`（Windows）或 `start.sh`（Linux/Mac）就能启动系统，以便不需要知道 Java/Node/Docker 是什么。
2. 作为**测试用户**，我希望启动脚本自动检测 Docker 是否安装，如果没装就给出明确的下载链接，以便我知道去哪下载。
3. 作为**测试用户**，我希望启动脚本自动等待数据库就绪，以便不会因为"数据库没起来"导致后端启动失败。
4. 作为**测试用户**，我希望启动成功后看到明确的访问地址（http://localhost:5173）和默认账号（admin / admin123），以便我知道下一步做什么。
5. 作为**测试用户**，我希望启动失败时看到友好的错误提示（"Docker 未启动，请打开 Docker Desktop"而不是"Connection refused"），以便我能自己排查。

### 自检命令

6. 作为**AI Agent**，我希望能执行 `doctor.bat --json` 获取机器可读的诊断报告，以便精准判断是端口冲突、内存不足还是数据库连接问题。
7. 作为**AI Agent**，我希望诊断报告包含退出码（0=健康，1=警告，2=严重），以便我决定是否能继续部署。

### 非交互模式

8. 作为**AI Agent**，我希望所有脚本支持 `--quiet` 参数，以便静默运行不阻塞。
9. 作为**AI Agent**，我希望所有交互式 Prompt 都有对应的 CLI 参数（如 `--admin-password=xxx`），以便我可以完全无人值守部署。

### 安装指南

10. 作为**非技术用户**，我希望有一份图文安装指南，每一步都有截图和"下一步"按钮，以便我不需要理解技术细节。
11. 作为**非技术用户**，我希望安装指南覆盖常见问题（Docker 装不上、端口被占用、忘记密码），以便我能自助解决。
12. 作为**非技术用户**，我希望安装指南有"一键复制"的命令块，以便我不需要手工输入命令。

## Implementation Decisions

### 一键启动脚本

**`start.bat`（Windows）**：
```batch
@echo off
echo === 排课系统一键启动 ===

REM 检测 Docker
where docker >nul 2>nul
if %errorlevel% neq 0 (
    echo ❌ 未检测到 Docker
    echo 请下载并安装 Docker Desktop: https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)

REM 启动服务
echo 启动数据库和后端...
docker compose up -d

REM 等待健康检查
echo 等待服务启动...
timeout /t 10 /nobreak >nul

REM 检查后端健康
curl -s http://localhost:8080/api/health >nul
if %errorlevel% equ 0 (
    echo ✅ 后端启动成功
) else (
    echo ❌ 后端启动失败
    echo 请查看日志: docker compose logs backend
    pause
    exit /b 1
)

echo === 启动完成 ===
echo 前端地址: http://localhost:5173
echo 后端地址: http://localhost:8080
echo 默认账号: admin / admin123
echo.
echo 首次使用请访问前端地址，按初始化向导操作
pause
```

**`start.sh`（Linux/Mac）**：类似逻辑，用 shell 语法。

### 自检命令

**`doctor.bat` / `doctor.sh`**：
```bash
#!/bin/bash
# 输出 JSON 格式诊断报告
{
  "docker": {"installed": true, "version": "24.0.0"},
  "ports": {"8080": "free", "5173": "occupied"},
  "memory": {"total_gb": 16, "available_gb": 8, "sufficient": true},
  "database": {"reachable": true, "migrated": true},
  "exit_code": 0
}
```

### 非交互模式

所有脚本支持 `--quiet` 参数：
- 不打印进度信息
- 不等待用户输入（无 `pause` / `read`）
- 错误时直接退出并返回非零退出码

### 内部自愈

- **数据库自动建表**：后端启动时自动执行 Flyway 迁移（已有）
- **自动创建 admin 账号**：首次启动时检测 `user` 表是否为空，为空则创建 admin/admin123
- **自动生成 JWT Secret**：如果环境变量 `JWT_SECRET` 未设置，自动生成随机字符串并写入 `.env`

### 就绪探针

后端 `Dockerfile` 添加健康检查：
```dockerfile
HEALTHCHECK --interval=5s --timeout=3s --retries=12 \
  CMD curl -f http://localhost:8080/api/health || exit 1
```

`docker-compose.yml` 添加依赖等待：
```yaml
backend:
  depends_on:
    postgres:
      condition: service_healthy
```

### 安装指南

**`docs/installation-guide.md`**：
- 分步图文教程（每步配截图占位 `![步骤1](./images/step1.png)`）
- 常见问题 FAQ（Docker 装不上、端口被占用、忘记密码）
- 一键复制命令块（```bash 代码块）
- 打印样式友好（便于教务老师打印出来照着做）

## Testing Decisions

### 测试接缝

1. **一键启动脚本**：在干净环境（无 Docker、有 Docker、有 Docker 但端口被占用）下测试脚本行为
2. **自检命令**：验证 JSON 输出格式、退出码、各检测项准确性
3. **非交互模式**：验证 `--quiet` 参数下脚本不阻塞
4. **安装指南**：人工审阅图文步骤的准确性

### 测试环境

- **干净虚拟机**：Windows 10 / Ubuntu 22.04，只装 Docker，不装 Java/Node
- **AI Agent 模拟**：用脚本模拟"低智商 Agent"执行安装流程，验证是否能成功

## Out of Scope

- 一键部署到云平台（Railway/Render 按钮）
- 安装向导 CLI（交互式命令行）
- 预构建镜像（用户不需要 build，直接 pull）

## Further Notes

### 参考实现

- **Plane**：`setup.sh` 交互式安装 + 首次访问 `/setup` 向导建号
- **Ghost**：`ghost install local` 全静默 + `ghost doctor` 自检
- **Cal.com**：`yarn dx` 一键命令 + 自动播种 + 打印默认账号
- **Documenso 反模式**：`${VAR:?err}` 严苛校验（要避免）
- **若依反模式**：依赖手工 GUI 操作（要避免）

### 交付物清单

- `start.bat` / `start.sh`：一键启动脚本
- `doctor.bat` / `doctor.sh`：自检命令
- `docker-compose.yml` 优化：健康检查、依赖等待
- `backend/Dockerfile` 优化：健康检查
- `docs/installation-guide.md`：安装指南
- `docs/images/`：截图占位目录
