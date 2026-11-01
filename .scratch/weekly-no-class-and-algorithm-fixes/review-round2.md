# Fresh review 第二轮及主模型追加反例

固定目标0ec3529，比较4753fc0..0ec3529。两位新复核者没有参与实现或首轮审查，只读复核，不运行测试。原实现者继续集中修复追加问题；算法判断仍由主模型保留。

## Standards

无新的可行动发现（硬规范0、需另报smell0）。核对了受控executor排队、显式warm-start settings、force新规则保护、类型身份集中、空选择不入payload、空group签名、capacity composable生命周期与删除成员的调用方。

复核未自行执行完整门禁、浏览器或真实PostgreSQL；31后端/213前端等执行证据见review-fix-delivery，不累加重复数量。

Standards：0项新增发现；首轮接受的规范/结构问题已有修复证据，完整运行门禁仍待主流程。

## Spec

1项P3文档问题：ADR0025末段写plan/verify共用node-budget，但verify不搜索，也不接受该参数；README已正确限定plan。主模型检查源码后接受：由同一原实现者把搜索预算/0-2-3状态限定plan，verify保持0/1校验契约。

其他核对无新代码问题：来源reason使用同一active matcher，重叠/暂停来源一致；SELECTED缺列表不会全校；空group前后端一致；模板/工作区、容量必要条件和queue重查未改变。未执行测试/构建/浏览器/PG，不作算法数学结论。

Spec：1项新增文档发现；最严重为CLI适用命令表述不准，已交集中修复，尚未在本记录写作时完成最终复验。

## 主模型独立新增：刷新后新增要求的group碰撞

原有nextKey同时产生临时entry/slot key与持久groupKey。新页面三条卡片会产生group-3/group-6/group-9；刷新后加载三卡只消费6个临时key，再新增卡会生成group-9，撞到已有第三条。四条要求的保存payload再次hydrate只剩三卡，独立暂停身份丢失。

主模型在0ec3529独立worktree新增weeklyGroupIdentity.spec.ts，以公开hydrate/addEntry/updateEntryValue/payload/re-hydrate测试，实际exit1、1项失败，断言是预期四卡、实际三卡。这个失败是业务往返反例，不是编译红灯。已恢复同一个review-fix implementer，要求预留已有groupKey、新生成避撞，不改已存编号/entry id，不引入后端协议或猜测历史意图。

后续：该有界补丁与ADR小修完成后，再让新的复核者看最后delta；完成后才冻结源码跑完整门禁。
