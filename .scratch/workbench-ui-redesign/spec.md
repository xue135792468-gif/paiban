# 管理员调代工作台 UI 重做

Status: ready-for-agent（2026-10-30）

## 背景与目标

现网工作台（票 01-07 交付）功能完整，但界面被用户判定为「反人类」：一屏纵向堆叠、面板分不清、左右对照与清单看不懂、调课窗口只能靠报错才知道。经两轮原型迭代（v1 → v2 → v3），用户选定 **v3a 留白版** 作为新界面方向。

本规格只重做前端界面：**后端接口、数据库、业务规则零改动**；复用现有 `/api/substitution-workbench/*` 与 `/timetable/*`。

## 设计依据（原型即视觉规格）

- 选定原型：`C:\Users\liu\.zcode\v2\visualizations\742542c2d05adefebf901d486182e3b320ecfaf178ae5e74b62defbfd3e8ebd0\v3a-airy.html`（HTML 片段，需包一层 html/body 查看；同目录 `v3b-dense.html` 仅作对照，不采用）
- 已验证：两版各 40/40 交互断言、0 控制台错误；三个布局 bug 已修（切换入口在表单上方且首屏可见、原因输入框单行约 40px、变化格序号与文字不重叠）
- 设计规则摘要：
  - 三步向导：① 选哪节课 → ② 怎么调 → ③ 核对并提交；一次只显示一步，顶部步骤条（当前蓝色、已完成绿勾），已选内容折叠成摘要条（带「修改」）
  - 面板层次：页面底 `#f5f7fa` → 白面板（1px `#e6e8ef`、14px 圆角、轻阴影）→ 面板内嵌区 `#f9fafb`；当前步骤面板左侧 3px 品牌蓝竖条
  - 第②步：两张动作卡；选中后就地展开表单，另一张收成右对齐小号虚线链接，位置必须在**选中卡与表单之间**（用户已因它在表单下方看不到而报 bug）
  - 第③步：一句话结论 → 跨科提醒+勾选门 → **课表变化可视化（视觉主角）**：每周一张迷你周表，变化格内「旧值灰色删除线 → 新值绿色」，①②编号 + 图例；跨周画两排（本周/下周）；文字明细（表格+按人清单）折叠在 `<details>`
  - 底部操作栏白底+分隔线，钉在内容区底部（内容区独立滚动，与票 08 一致）
  - 320px：导航隐藏、主课表与迷你周表切为按天列表、无横向溢出
- 明确沿用现有产品主题（浅色）；不新增深色模式。

## 接口契约（全部已存在，禁止改动）

| 用途 | 端点 |
| --- | --- |
| 教师列表/课表 | `GET /api/teachers`、`GET /api/timetable/teacher/{id}?view=latest&date=` |
| 代课候选 | `GET /api/substitution-workbench/candidates?entryId=&lessonDate=` |
| 调课目标槽位 | `GET /api/substitution-workbench/swap-targets?entryId=&lessonDate=&targetDate=` |
| 生成调课候选 | `POST /api/substitution-workbench/swap-plans` |
| 代课预览凭证 | `POST /api/substitution-workbench/previews` |
| 调课预览凭证 | `POST /api/substitution-workbench/swap-previews`（对照：`GET .../previews/{id}/swap-compare`） |
| 提交/确认（代课） | `POST .../previews/{id}/submit-approval`、`POST .../previews/{id}/confirm` |
| 提交/确认（调课） | `POST .../previews/{id}/submit-approval-swap`、`POST .../previews/{id}/confirm-swap` |
| 窗口配置 | 设置项 `substitution.search-window-days`（1–30，默认 7；窗口 = 来源日起含当天） |

## 非目标

- 不改后端/数据库/求解器/校验器；发现接口缺口时停下并报告，不得改 API 迁就界面
- 不改权限模型（沿用 `WORKBENCH_MANAGER_PERMISSIONS`）；不改路由
- 不做深色模式、不做移动端专属布局（只做 320px 可用）
- 不迁移/转换历史调代数据

## 票据与依赖

| 编号 | 标题 | Blocked by |
| --- | --- | --- |
| 01 | 向导骨架 + 选课 + 动作表单（代课/调课、窗口日期块、拖拽预选） | None |
| 02 | 确认页：结论、跨科门、课表变化可视化、提交/确认 | 01 |
| 03 | 既有 e2e 迁移 + 无障碍/320px + 帮助文案 + 全量门禁 | 01、02 |

## 验收缝

- 组件/纯函数单测（窗口日期、变更格计算、列表映射）
- Playwright：三步完整链路（代课、同周调课、跨周调课、方案 A/B、取消零副作用、再次调整）
- 全量门禁 `scripts/verify.sh --full`
