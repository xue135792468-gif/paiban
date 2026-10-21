# 排课系统 Agent 部署提示词（无 GitHub 账号版）

## 适用场景

- 用户没有 GitHub 账号
- 用户没有安装 git
- 用户希望 AI Agent 全自动完成部署
- 用户只有一台 Windows/Mac 电脑 + Docker Desktop

## 部署原理

GitHub 公开仓库的 ZIP 可以直接通过 URL 下载，无需账号：
```
https://github.com/{用户名}/{仓库名}/archive/refs/heads/main.zip
```

## 一键部署命令

### Windows

```powershell
# 1. 下载 ZIP（无需 GitHub 账号）
Invoke-WebRequest -Uri "https://github.com/xue135792468-gif/paiban/archive/refs/heads/main.zip" -OutFile "paiban.zip"

# 2. 解压
Expand-Archive -Path paiban.zip -DestinationPath .
cd paiban-main

# 3. 一键启动（静默模式）
start.bat --quiet --admin-password=AdminPass123 --timeout=300
```

### macOS / Linux

```bash
# 1. 下载 ZIP（无需 GitHub 账号）
curl -L -o paiban.zip "https://github.com/xue135792468-gif/paiban/archive/refs/heads/main.zip"

# 2. 解压
unzip paiban.zip
cd paiban-main

# 3. 一键启动（静默模式）
chmod +x start.sh
./start.sh --quiet --admin-password=AdminPass123 --timeout=300
```

## 完整提示词（可直接复制给 Agent）

```markdown
请帮我部署排课系统到本地电脑。我的电脑是 [Windows/Mac]，已经安装并启动了 Docker Desktop。

请执行以下步骤：

1. 下载排课系统 ZIP 包（无需 GitHub 账号）：
   - URL: https://github.com/xue135792468-gif/paiban/archive/refs/heads/main.zip
   - 保存为 paiban.zip

2. 解压到当前目录：
   - Windows: powershell -Command "Expand-Archive -Path paiban.zip -DestinationPath ."
   - Mac: unzip paiban.zip

3. 进入解压后的目录：
   - cd paiban-main

4. 一键启动系统：
   - Windows: start.bat --quiet --admin-password=AdminPass123 --timeout=300
   - Mac: chmod +x start.sh && ./start.sh --quiet --admin-password=AdminPass123 --timeout=300

5. 等待启动完成（首次需要 5-15 分钟，看到 "[完成] 排课系统已启动" 表示成功）

6. 启动成功后，打开浏览器访问 http://localhost:5173，用 admin / AdminPass123 登录

注意事项：
- 必须使用 --quiet 参数避免交互阻塞
- 首次启动需要下载依赖，耐心等待
- 如果失败，执行 doctor.bat --json (Windows) 或 ./doctor.sh --json (Mac) 查看诊断报告
```

## 故障排查

如果启动失败，执行自检命令：

**Windows**：
```powershell
doctor.bat --json
```

**macOS / Linux**：
```bash
./doctor.sh --json
```

**常见退出码**：
- `0`：健康，可以访问 http://localhost:5173
- `1`：警告（如端口被占用），通常不影响使用
- `2`：严重问题（如 Docker 未启动），需要修复

## 默认账号

- 用户名：`admin`
- 密码：`AdminPass123`（或你在 `--admin-password=` 中设置的密码）

## 停止系统

```bash
docker compose down
```

---

**文档版本**：v1.0  
**更新日期**：2026-10-21  
**适用系统**：排课系统 v1.0（main 分支）
