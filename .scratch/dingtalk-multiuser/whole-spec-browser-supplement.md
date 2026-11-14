# 浏览器实据补核（Spec 轴，只读）

核于集成 `.scratch/dingtalk-multiuser/logs/whole-fixes-browser-run1/`：`playwright.exit.txt`=EXIT=0；`playwright.log` 尾行「32 passed (4.3m)」；`source-stability.txt`=SOURCE_IDENTICAL=1；`source-refs.txt` HEAD_BEFORE=HEAD_AFTER=`52cbf131…`（运行期间源未动）。

- `post-integration-source-proof.txt`：COMMIT=`7413a01469cf6dbd3460d6c16e3709ea0cdd0666`、CHECKED_SOURCE_FILES=1008、MISMATCHES=0、EXTRA_COMMITTED_FILES=0、POST_INTEGRATION_SOURCE_IDENTICAL=1；集成 HEAD 即 7413a01，其父 261be4a 合入候选 52cbf13（receipt-only 提交，树=52 树）。
- 运行与源相符：M1 行为边界（默认本人课表、≤900 仅 drawer 入口、同 `visibleGroups/activeMenu`、桌面 `layout-menu`+tour 锚点、手机独立 testid 无 tour、点菜单/route 关闭、无越权菜单）在 52cbf13 源中即为本轮实跑对象，非事后补源。
- **保留 P3-1（采纳主控交付说明口径）**：`substitution:submit`+`substitution:approve` 但缺完整工作台权限的自定义角色，菜单落 `/substitution/requests`；「待审批」页签仍在（`SubstitutionsView.vue:49,89`），不越权，预置三角色不受影响。不改源。
- 边界：全量 `verify --full` 未跑；H2/Fake/离线手机视口不证明真实 PostgreSQL、钉钉租户或手机送达。本补核不构成这些项通过。
