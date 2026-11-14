> 主控边界校正：下文顾问所谓「实机」实际是Playwright离线Fake、手机仿真视口，未在真实手机或钉钉租户执行。「准予收口」只作顾问建议；主控采纳范围仅为这三个阶段截图未见阻断性可用性问题、补滚动取证即可，不等于全面移动端无缺陷。列数/总宽度为顾问描述，不作为验收数字；产品源码零变更另以Git diff核对，SOURCE_IDENTICAL只表示测试前后源相同。

# T08 移动端实机截图与 UI 可用性顾问收口结论
- **最终判定（准予收口）**：**当前手机端流程无可用性缺陷，保持产品源码零改动（`SOURCE_IDENTICAL=1`）**；仅通过 E2E 滚动视口即可完整取证，最终验收与说明书应采用 `t08-browser-run2/t08-phone-effective.png`。
- **两轮实机截图核验事实**：
  1. `run1/t08-phone-submit.png` 与 `run1/t08-desktop-change-teacher.png`：手机端 `SubstitutionRequestForm.vue` 表单（日期 `2026-11-16`、课节 `第1节 · 数学csh0hd · 高一...`、期望 `李老师csh0hd`、原因 `手机整链请假-niarc5n1`）及桌面教务改派 `接课终验-niarc5n1` 审批弹窗均完整清晰。
  2. `run1/t08-phone-effective.png`：因未对内部滚动容器定位，画面停留在顶部重置后的空表单（`2026-11-13`），**未拍到下方「我的申请」表格**。
  3. `run2/t08-phone-effective.png`（`验收申请人-browser_bgalgcex`）：E2E 显式纵向滚动 `el-main` 并横向定位 `el-table` 后，**同一手机视口下半屏「我的申请」卡片完整呈现 `课程：数学oaiolz`、`代课教师：期望：李老师oaiolz / 实际：接课终验-bgalgcex` 与绿色标签 `状态：已生效`**，字迹清晰无裁切。
- **代码与设计合理性说明**：
  1. `MainLayout.vue:146-149, 208-213` 将 `.shell` 固定为 `100vh`、由 `el-main[data-testid="layout-main"]` 承担纵向滚动；`SubstitutionMineTable.vue:11-56` 共 10 列（总宽 `1330px`），手机端通过 `.el-table__body-wrapper` 自然横滚浏览属标准响应式表格行为，并非功能缺陷。
  2. `SubstitutionMineTable.vue:26-46` 中「课程（`90px`）+ 代课教师（`150px`，含 `changedTeacher` 期望/实际双行）+ 状态（`100px`）」三列合计 `340px`，完全适配手机单屏宽度，无需改动任何业务组件或重设计移动端布局。
