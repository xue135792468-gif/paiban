# 01: 一键启动脚本（start.bat / start.sh）

**What to build:** 实现 Windows 和 Linux/Mac 的一键启动脚本：自动检测 Docker、启动服务、等待健康检查、打印访问地址和默认账号。支持 `--quiet` 静默模式。完成后，测试用户下载代码后双击 `start.bat`（Windows）或执行 `./start.sh`（Linux/Mac）即可启动系统，无需知道 Java/Node/Docker 是什么。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `start.bat`（Windows）：检测 Docker、启动 `docker compose up -d`、等待健康检查、打印访问地址和默认账号
- [ ] `start.sh`（Linux/Mac）：同上逻辑，用 shell 语法
- [ ] 环境检测：Docker 未安装时给出明确下载链接（https://www.docker.com/products/docker-desktop）
- [ ] 就绪探针：等待后端 `/api/health` 返回 200（最多等待 60 秒）
- [ ] 错误处理：启动失败时打印友好错误提示（"Docker 未启动，请打开 Docker Desktop"而不是"Connection refused"）
- [ ] 静默模式：支持 `--quiet` 参数，不打印进度信息、不等待用户输入
- [ ] 端口检测：检测 8080 和 5173 端口是否被占用，被占用时给出提示
- [ ] 内存检测：检测可用内存是否 ≥ 2GB，不足时给出警告
- [ ] 文档：README 中添加"一键启动"章节，说明 `start.bat` / `start.sh` 用法
- [ ] 测试：在干净虚拟机（只装 Docker，不装 Java/Node）验证脚本能成功启动系统
