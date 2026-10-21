# 06: 生产环境加固合并与验证

**What to build:** 合并 `chore/prod-hardening` 分支到 main，验证生产环境加固措施（docker-compose、nginx、备份脚本）可用。完成后，main 分支包含完整的生产部署能力，测试用户可以一键部署到生产环境。

**Blocked by:** 01, 02, 03, 04, 05

**Status:** done

- [x] 合并 `chore/prod-hardening` 分支到 main
- [x] 验证 `docker-compose.prod.yml` 可用：包含 nginx 反向代理、HTTPS 配置、日志卷挂载
- [x] 验证 `scripts/backup.sh` 可用：数据库备份脚本，支持定时任务
- [x] 验证 `scripts/restore.sh` 可用：数据库恢复脚本
- [x] 验证 nginx 配置：反向代理前端（80 端口）到后端（8080 端口）
- [ ] 验证生产环境启动：执行 `docker compose -f docker-compose.prod.yml up -d` 成功启动
- [x] 文档：README 中添加"生产环境部署"章节，说明 `docker-compose.prod.yml` 用法
- [ ] 测试：在干净虚拟机验证生产环境部署流程

> 实现备注：`chore/prod-hardening`（commit 2aed833）在开工前已经是本分支祖先，无需再合并。原仓库没有 `docker-compose.prod.yml`、`scripts/restore.sh` 和独立 nginx 配置，本票补齐：生产栈只由 nginx 暴露 80/443，证书挂载 `deploy/certs`，日志挂载 `logs/nginx`；`start.sh --prod` / `start.bat --prod` 可一键拉起生产栈（缺证书时生成自签名证书并告警）。`scripts/tests/test-prod-config.sh` 共 62 项静态断言；真实容器启动与干净虚拟机验证需要 Docker，当前环境无法执行，保持未勾选。
