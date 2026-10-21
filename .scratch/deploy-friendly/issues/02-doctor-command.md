# 02: 自检命令（doctor.bat / doctor.sh）

**What to build:** 实现机器可读的自检命令：检测 Docker、端口、内存、数据库连接、表结构迁移状态，输出 JSON 格式诊断报告，返回明确退出码（0=健康，1=警告，2=严重）。完成后，AI Agent 执行 `doctor.bat --json` 获取诊断报告，精准判断是端口冲突、内存不足还是数据库连接问题。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `doctor.bat`（Windows）：检测以下项目并输出 JSON
- [x] `doctor.sh`（Linux/Mac）：同上逻辑，用 shell 语法
- [x] 检测项：
  - Docker：是否安装、版本、是否运行
  - 端口：8080（后端）、5173（前端）、5432（PostgreSQL）是否被占用
  - 内存：总内存、可用内存、是否 ≥ 2GB
  - 数据库：是否可连接、表结构是否已迁移（Flyway 版本）
  - 磁盘：E 盘（项目盘）剩余空间是否 ≥ 1GB
- [x] JSON 输出格式：
  ```json
  {
    "docker": {"installed": true, "version": "24.0.0", "running": true},
    "ports": {"8080": "free", "5173": "occupied", "5432": "free"},
    "memory": {"total_gb": 16, "available_gb": 8, "sufficient": true},
    "database": {"reachable": true, "migrated": true, "flyway_version": "1.0.0"},
    "disk": {"e_drive_free_gb": 25, "sufficient": true},
    "exit_code": 0
  }
  ```
- [x] 退出码：0=健康，1=警告（如端口被占用但不影响启动），2=严重（如 Docker 未安装）
- [x] `--json` 参数：输出 JSON 格式；不加参数时输出人类可读格式
- [x] 测试：在各种异常场景下验证诊断准确性（Docker 未装、端口被占用、内存不足、数据库未启动）

> 实现备注：端口占用只作为信息项，不参与退出码计算——系统正常运行时 8080/5173/5432 本来就被自己的容器占用。退出码 2 对应 Docker/数据库/磁盘问题，1 对应内存不足或迁移缺失，与规格示例（端口 occupied 且 exit_code 0）一致。`scripts/tests/test-doctor.sh` 共 21 项断言，覆盖 JSON 契约、退出码、无 Docker、端口占用与 PowerShell 变体。
