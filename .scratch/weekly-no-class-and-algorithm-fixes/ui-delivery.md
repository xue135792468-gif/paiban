# 票04前端子任务交付

日期：2026-11-01；主模型在codex/weekly-t04-ui独立实现，基点77113c5。票04整体仍待后端合并及真实浏览器验收。

## 行为

- 消费目录hardOnly：类型与条目均显示“必须留空”，保留启停，隐藏软硬/权重选择；草稿状态层也拒绝通过setHard/setEntryHard降为尽量。
- 班级选项沿既有对象目录传到条目控件，摘要显示班名。第8/9节仍是全天连续节次。
- 消费草稿详情blockedSlots（classId/periodId/reason），仅在选定行政班视图标记禁排格；教师/教室视图不被误标为整体禁排。
- 空格显示“不上课”和原因，前端不派发拖入事件；已占格保留完整课程卡片、冲突文本，并允许向其他位置拖出。
- 草稿网格增加稳定课节/课程身份属性，端到端测试通过可观察课程身份操作；不向产品界面显示实现信息。
- 帮助文案区分固定留空、未排任务和具体日期停课。多班/全校与容量说明由05扩展。

## 实测

- 红：仅先加DraftGrid与ConstraintTypePanel新行为测试，vitest两文件19项中3失败、16通过，exit=1；分别缺少不上课标记、存量课冲突标记、固定必须与班级摘要。
- 绿：vitest四文件（DraftGrid、ConstraintTypePanel、useConstraintDraft、entrySummary）53项通过，exit=0；包括不能降为尽量且允许暂停的保存数据行为。
- npm run lint exit=0；npm run build exit=0（只有既有bundle体积提示）；SFC体积检查exit=0，DraftEditorView仍小于600行。
- playwright test --list constraints-weekly-no-class.spec.ts：1个chromium用例成功发现，exit=0。仅为发现/语法检查，尚未运行浏览器；不能当作端到端通过证据。
- 已新增端到端完整流程：界面设置/保存/刷新→自动排课→查看不上课格→拖入不改变课程及API拒绝→发布合法草稿。待与后端/core合并后通过scripts/e2e.sh实跑。

## 待验收

后端新目录、hardOnly与blockedSlots契约接通，实际API/浏览器流程，以及05的全校/多班分组恢复。没有访问生产或真实AI服务，没有改变正式课表。
