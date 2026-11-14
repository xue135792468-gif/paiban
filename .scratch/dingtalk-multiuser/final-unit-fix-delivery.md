# 第二轮门禁唯一失败修复（productTrim.spec.ts）

## 原因
设置面板下已随票 02 增加「账号审计」页，但 productTrim.spec.ts 仍按旧三路由精确列表断言，
主控第二轮 full 的 Vitest 在 productTrim.spec.ts:92 失败（803 中 802 通过）。产品路由本身正确，测试快照过期。

## 改动（唯一源文件）
frontend/src/router/productTrim.spec.ts：settings 路径精确列表改为 general/users/account-audit/recycle-bin；
新增 account-audit 路由的 name 与 meta.permission === "audit:view:account" 精确断言；其余精确断言原样保留；更新旧注释。
router 产品源码未改。

## 证据
red：主控 integration feature/logs/final-full-run2/verify.log（真实完整红，未复演）。
green：npx vitest run --no-file-parallelism 跑 productTrim.spec.ts + roleAccess.spec.ts + navigation.spec.ts，
exit 0，Test Files 3 passed、Tests 20 passed（日志 logs/final-unit-fix/vitest.log / vitest-exit.log）。
source-hashes 语义：source_before=bec5ad32…（修改前编辑基线/旧代码）；source_after=714aa18d…（新代码）。
该收据 20:31:25 落盘，早于 20:31:50 测试启动，故 after 是预先记录的待跑源，也是本次实际所跑源；
它是编辑基线对照，不是运行前后冻结收据，不据此声称 SOURCE_IDENTICAL；运行前后强冻结由主控统一预检 runner 覆盖。

边界：只改测试，未跑全量/Maven/E2E，不 push/部署，不删路径与缓存。
