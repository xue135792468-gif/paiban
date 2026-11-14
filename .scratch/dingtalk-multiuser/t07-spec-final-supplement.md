# 07 Spec 轴最终极小补充（原始）

1. 源 snapshot 只读 FF 至 016859bc35a31f2cf5c93e2280e395cbad2b6d62（HEAD 核对一致）；`git diff b253112...016859b -- frontend backend` 仅 `frontend/e2e/dingtalk-roles.spec.ts` 2 增 2 删，其余为 merge/报告/归档文档。资源空闲 6.22/15.73GB。未改源、未跑测试/构建/网络/服务、未删除。
2. **正向断言保留**：`dingtalk-roles.spec.ts:44` 改后仍断言超管在 `/settings/general` 看到「账号」页签（`.page-tab[href="/settings/users"]` 可见），随后仍跳 `/settings/users` 并断言「用户管理」标题，原「账号页可见」意图未削弱，定位更精确。
3. **负向断言保留**：`:72` 仍要求教务该页签 `toHaveCount(0)`；页签由 `sectionTabs.ts:29-34` 的 `user:manage`/`audit:view:account` 门控，未改。未用 `first()`/`nth()` 掩盖，缺项时仍会真实失败而非放行。
4. 根因可核：`sectionTabs.ts:30,33` 的「账号」与新增「账号审计」文本都含「账号」，原 `hasText:'账号'` 会双匹配（run1 日志实测 2 elements），改按唯一 href 定位。
5. 规格符合性不变：07「钉钉区块与账号管理只对超管可见、教务 403/不可见」的断言对象未改，未触碰权限、wire 契约、数据或产品源码；无范围外行为新增。
6. 新发现：Spec P1 0、P2 0、P3 0。仅记一条不计数观察：`[href="/settings/users"]` 依赖路由渲染原样 href，若将来加部署 base 前缀该断言会显式失败（非静默通过），届时需同步；E2E 在根路径运行，当前无影响。
7. 原计数与最坏项不改写：Spec 原 P3=2（P3-1 已裁定维持、P3-2 已修复关闭）；我上一增量新增 P3=0，无 J8 类新判断，不重开 40s/60s 边界。
8. 运行证据（只读核对）：`logs/t07-review-fixes-browser-run2` 的 `playwright.exit.txt=EXIT=0`、`playwright.log` 末行 `11 passed (1.1m)`、`source-stability.txt=SOURCE_IDENTICAL=1`、before/after 哈希清单逐字节相同，`source-selector-patch.txt` 与该两行完全一致；`logs/t07-final-review-input/post-commit-source-stability.txt=POST_COMMIT_SOURCE_IDENTICAL=1`。run1 保留 `1 failed / 10 passed` 与 strict-mode 原文，未被覆盖。
9. 边界：`doctor.exit.txt=2`（无 Docker/5432），未有真实 PostgreSQL、真实钉钉租户/手机、full verify 或部署证据；本次 11 通过仅覆盖四个受影响 spec，不等于八票全量。学校实机清单仍由手册执行，非本票缺失。
10. 结论：该两行不引入新的明确规格问题，无需再改；本轴不就 07 放行表态。