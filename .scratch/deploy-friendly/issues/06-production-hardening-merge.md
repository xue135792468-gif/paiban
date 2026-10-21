# 06: 生产环境加固合并与验证

**What to build:** 合并 `chore/prod-hardening` 分支到 main，验证生产环境加固措施（docker-compose、nginx、备份脚本）可用。完成后，main 分支包含完整的生产部署能力，测试用户可以一键部署到生产环境。

**Blocked by:** 01, 02, 03, 04, 05

**Status:** ready-for-agent

- [ ] 合并 `chore/prod-hardening` 分支到 main
- [ ] 验证 `docker-compose.prod.yml` 可用：包含 nginx 反向代理、HTTPS 配置、日志卷挂载
- [ ] 验证 `scripts/backup.sh` 可用：数据库备份脚本，支持定时任务
- [ ] 验证 `scripts/restore.sh` 可用：数据库恢复脚本
- [ ] 验证 nginx 配置：反向代理前端（80 端口）到后端（8080 端口）
- [ ] 验证生产环境启动：执行 `docker compose -f docker-compose.prod.yml up -d` 成功启动
- [ ] 文档：README 中添加"生产环境部署"章节，说明 `docker-compose.prod.yml` 用法
- [ ] 测试：在干净虚拟机验证生产环境部署流程
