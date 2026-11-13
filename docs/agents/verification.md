# 验证与运行收据

## 选择入口

日常迭代使用 `scripts/verify.sh --quick` 或模块定向命令。收据工具需要 Python 3.9+。最终冻结验证使用：

```bash
bash scripts/verify-run.sh <run-id> --full
```

需要真实 PostgreSQL 的发布门禁使用 `--deploy`；没有 Docker 就失败。`--quick` 的后端只有编译，不代表后端测试或浏览器验收通过。收据工具的选项见 `bash scripts/verify-run.sh --help`。

本地门禁先执行仓库检查和前端 lint/build/unit，再执行后端 compile/verify，Playwright 最后执行。快阶段失败即停止，避免为前端契约错误重复等待后端长套件。CI 仍按现有独立 job 并行执行；编排回归用假命令检查顺序，不充当业务测试证明。

## 冻结验证

运行前提交本次实现与说明，保证 tracked 工作树干净；源范围内的未跟踪非忽略文件也要先处理。不要通过忽略本次源文件来满足门槛。

源范围为 `backend/`、`frontend/`、`scripts/`、`.githooks/` 的 tracked 文件。HEAD 绑定代码版本，字节 SHA256 检查实际执行前后的文件内容；这是运行快照，不是修改前旧代码与修改后新代码的对照。工具不会自动 stage 或 commit。

原始日志、TMP/TEMP、npm 缓存和截图都在当前工作树 `.runtime/verification/<run-id>/`；可归档元数据在 `.scratch/verification/<run-id>/`。run-id 为单段标识，已有运行不覆盖。输出路径解析到工作树外时停止。

## 读取结果

命令退出码与收据工具退出码分别记录。只有命令成功、源码前后一致且归档有效时工具才返回 0；源变化返回 86，其他命令失败保留原失败退出码。检查输出不能替代真实外部服务、手机或 PostgreSQL 的验收，原命令的跳过项仍须单列。

归档只包含版本、时间、退出码、源码清单与日志摘要哈希等元数据；完整原始日志留在本地，不自动进入 Git。局部 `.gitattributes` 保留收据原始字节。

提交收据时，pre-commit 会调用：

```bash
bash scripts/check-verification-receipts.sh --staged
```

这校验的是 Git 索引中的字节；无参数校验 HEAD 中已归档的收据。它检查完整性，不将失败历史改成通过，也不将完整性通过解释成业务测试成功。README 或其他收据内容改变后，需重新生成对应校验表。

## 角色与入口

关键用户流程的验收从该角色、设备的默认落地页开始，使用真实可见控件完成导航。深链接、刷新恢复和拒绝访问是独立场景。已有导航回归应继续保留，语义判断由 `CODING_STANDARDS.md` 的测试条款负责；不机械禁止所有 `page.goto`。
