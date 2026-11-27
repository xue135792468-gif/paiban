# Standards 浏览器运行补核（browser-run1，只读收尾）

- 只读核对 `integration/.scratch/dingtalk-multiuser/logs/whole-fixes-browser-run1`：`playwright.exit.txt`=`EXIT=0`；`playwright.log` 末行 `32 passed (4.3m)`，含 `dingtalk-acceptance.spec.ts:21`（ok 4）与 `dingtalk-mobile-navigation.spec.ts:12`（ok 9），tour 两条亦 ok（31/32）。
- 源冻结：`source-stability.txt`=`SOURCE_IDENTICAL=1`，`source-hashes-before/after` 内容逐行相同且两者 SHA256 同为 `3DC566B6…6806D3`；`source-refs.txt` HEAD 前后均 `52cbf131382f769a25dc5e7b781ad4f8abcbebce`。
- 合并后源证明：`post-integration-source-proof.txt` 为 `COMMIT=7413a01…`、`CHECKED_SOURCE_FILES=1008`、`MISMATCHES=0`、`EXTRA_COMMITTED_FILES=0`、`POST_INTEGRATION_SOURCE_IDENTICAL=1`；另核 `261be4a^{tree}`=`52cbf13^{tree}`=`2fceb80a…a863`，`52cbf13…7413a01` 仅增 `.scratch/.../whole-fixes-merge-receipt.md`，属收据文档。
- `SHA256SUMS.txt` 覆盖 9 项且已存在；证据目录 `evidence/whole-fixes-browser-run1` 实有 25 个 PNG（含 drawer/phone submit/effective）。
- 结论：浏览器运行与源一致性证据自洽，支持「通过」的**定向浏览器切片**，且不影响本轴结论。
- 计数保持：**Hard P1 0 / P2 0 / P3 0**（H1 已关）；Judgement **P3=1，仅 J4**（主控不采纳、非延期、未修复），不阻止本地主控 full。
- 自纠：mobile delta 报告与聊天中的 `submission:submit` 系笔误，实际源码为 **`substitution:submit`**（`navigation.ts:113`、`constants/permissions.ts`）；原 raw 不重写，计数不变。
- 边界：未复跑测试、未重读全仓、未改源；full 门禁仍未跑，真实 PostgreSQL/钉钉租户/手机未验证，**不宣称全量通过**。
