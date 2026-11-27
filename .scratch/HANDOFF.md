# 接手说明（HANDOFF）

## 当前状态（2026-11-27）

- **第二波 UX 改造已合并 `main`（[PR #36](https://github.com/xue135792468-gif/paiban/pull/36)，合并提交 `5deccb1e`），尚未部署生产。**
- 交付摘要与验收证据见 [qa-ux-wave2/spec.md](qa-ux-wave2/spec.md) 与 [qa-ux-wave2/acceptance-report.md](qa-ux-wave2/acceptance-report.md)（按 `docs/agents/issue-tracker.md` 约定，验收统计数字只在报告保留，不在此重复）。
- **改动边界**：纯前端表示层与页面状态编排，未改后端求解器、数据库迁移（Flyway 保持 **v59**）或 REST 契约；整合时保留主分支的任课表、钉钉连接测试与通知发送记录。

## 线上基线与部署红线

- **部署跑册**：见 [docs/runbooks/deploy.md](../docs/runbooks/deploy.md)；动手前先全量备份数据库（`pg_dump | gzip > ~/backups/paiban/paiban-<UTC>.sql.gz`），远程命令合并为单次 SSH，长构建放后台。
- **Nginx 端口核对**：服务器曾因本地 override 导致 `up -d` 后映射变成 `18080/18443` 引发公网 502，每次重建后务必先查 `docker port paiban-nginx-1` 确认为 `80/443`。
- **单用户安装包**：`scripts/install.sh` 与 `scripts/package-single-user.sh` 已落盘，但本机无 Docker，**尚未在真实 Linux + Docker 上端到端验证**，首次异地交付前需先在临时 VPS 演练。
- **历史调代数据**：服务器历史旧格式调代记录为 0；如未来遇到存量旧格式转换，须先跑预检并完成教务签字后再执行。

## 已知待办与后续项

1. **发布上线**：按 `docs/runbooks/deploy.md` 将 `main`（`5deccb1e` 及之后的护栏更新）部署到生产。
2. **修复 3 个已观察到的 CI 偶发抖动**：
   - 调代审批 E2E：`getByText('申请已提交，等待教务审批')` 同时命中 toast 与页面状态节点导致 strict mode 报错，需按 `CODING_STANDARDS.md` 加上容器作用域。
   - 后端集成测试 `AiImportIntegrationTest.mappingRejectsATargetOfTheWrongType`（偶发 200 vs 400）。
   - 后端并发测试 `SubstitutionApprovalConcurrencyTest.concurrentApproveAndRejectOverHttpLeaveExactlyOneDecision`。
3. **非阻塞历史复审后续**：
   - [phase10-dingtalk-org-sync/issues/03-review-follow-ups.md](phase10-dingtalk-org-sync/issues/03-review-follow-ups.md)（`ready-for-agent`）
   - [rule-groups/review-fixes.md](rule-groups/review-fixes.md)（Hark P2 建议）
   - [dingtalk-multiuser/issues/09-temporary-cache-cleanup.md](dingtalk-multiuser/issues/09-temporary-cache-cleanup.md)（保留工作区说明）

## 历史归档

- 2026-10-25 ~ 2026-11-15 的详细版本日志、迁移演进与历次部署回执已完整归档至 [HANDOFF-archive.md](HANDOFF-archive.md)。
