# 03: 内部自愈初始化（自动建库/迁移/播种/密钥生成）

**What to build:** 实现应用启动时的内部自愈能力：自动建库、自动迁移、自动创建 admin 账号、自动生成 JWT Secret。完成后，首次启动时系统自动完成所有初始化工作，无需人工执行 SQL 或配置环境变量。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] 数据库自动建表：后端启动时自动执行 Flyway 迁移（已有，验证可用）
- [x] 自动创建 admin 账号：首次启动时检测 `user` 表是否为空，为空则创建 admin/admin123 并打印到控制台
- [x] 自动生成 JWT Secret：如果环境变量 `JWT_SECRET` 未设置，自动生成 64 位随机字符串并写入 `.env` 文件
- [x] 安全默认值：所有内部密钥（如数据库密码、JWT Secret）在缺失时自动生成，避免 `${VAR:?err}` 崩溃
- [x] 初始化日志：所有自动初始化操作打印到控制台（"已创建默认账号 admin/admin123"、"已生成 JWT Secret 并写入 .env"）
- [x] 幂等性：重复启动时不重复创建账号、不重复生成密钥
- [x] 单元测试：验证自动创建账号、自动生成密钥的幂等性
- [x] 集成测试：在干净数据库下启动应用，验证所有初始化自动完成

> 实现备注：宿主机路径由 `start.sh` / `start.bat` 写入 `.env`；直接 `docker compose up` 时由 `backend/secret-bootstrap.sh` 生成并持久化到 `./data/app/secrets.env`（跨重启复用）。占位值（`replace-with*` 等）一律视为缺失，避免把示例密钥带进生产。`IdentityDataInitializerTest`（6 项）与 `JwtServiceTest`（6 项）覆盖首启、幂等与占位密钥拒绝；`test-secret-bootstrap.sh` 59 项断言覆盖生成、复用与掩码。
