# 两轴复核第一轮与主模型处置

固定产品源码89f3eef，比较4c1cc19...89f3eef。E2E标签点击修正1acf0e3随后3/3通过，不改产品逻辑。复核者独立、只读，未运行测试；主模型保留最终算法判断。以下两轴分别记录，不合并排序。

## Standards

复核报告8项（1项规范问题、7项smell/设计建议）：

1. 容量排队测试借真实30秒作业制造队列、断言瞬时PENDING，违背并发/状态机用受控交错的规范。**接受并修复**：捕获任务执行入口，保存规则后再明确执行，以HTTP状态与落库不变量验收。复核者称“一课通常立即最优终止”并不准确：当前配置仅time limit；这不影响对时序依赖的改进。
2. 新类型字面量散落多个前端模块（Repeated Switches）。**接受集中身份判断**，移到既有weeklyScope模块；不为尚不存在的第二类规则新增通用CLASS_SET目录协议。
3. provider与checker各自遍历真实内部空位（Duplicated Code）。**保留的判断性建议，非未修缺陷**：两者分别处理增量流事实与独立快照，输入结构不同；共享业务匹配、保留独立遍历，并用同输入比较公开评分/校验，是本规格明确选定的证据边界。本轮不合并数学实现，避免削弱独立对照。
4. warm-start便捷重载隐式读取ThreadLocal默认规则。**接受并修复**：新warm-start入口必须显式settings；既有build包装属于前批明确wontfix且性能基准仍用，保留并显式defaults，不暗读ThreadLocal。
5. force保护与诊断代码使用独立字符串（Primitive Obsession）。**接受最小修复**：新规则身份统一用ConstraintType.CLASS_UNAVAILABLE.name()；不改旧25类公共诊断DTO。
6. preview把planned配置转换成未持久化entity再走Settings解析（Data Clumps）。**保留的适配选择，非当前缺陷**：这是已设计的边界转换，未写库，字段与最终保存同源，并复用唯一解析器。另起solver依赖service的planned解析器反而增加分叉；HTTP preview/save/start一致性已有覆盖。
7. 无调用方的两个DTO便捷构造与public availablePositions（Speculative Generality）。**接受删除**，canonical JSON结构保持。
8. CapacityNotice直接维护请求状态（前端边界）。**接受移入composable**，保留旧响应丢弃、卸载清理、错误提示的行为测试。

已审后端main、前端constraints/draft/view主码与测试接缝，参考CODING_STANDARDS、ADR0025/0028/0030；未逐行复核交付文档、未执行测试或判断整体算法合格性。

Standards：8项原始发现；接受6项作有界改进，2项按上述理由保留；最严重项为排队测试的时序依赖，待集中修复和fresh review。

## Spec

复核报告4项：

1. 规格“可查看范围/原因”，现tooltip仅固定“本班每周不上课时段”。**接受并修复**：显示匹配的全校/指定班级来源，重叠时两者都显示，暂停来源消失，不改变容量或评分。
2. issue04仍有未勾选证据。**已补落票**：backend-delivery记录真实2班×2时段工作区、模板映射和审计；对应checkbox现已更新。属于审查旧快照的文档状态，并非功能缺失。
3. CLI --node-budget/SEARCH_BUDGET与新组合法性校验被指超范围。**主模型判为必要实现选择**：预算耗尽不能伪报无解，默认50000节点/200ms未放宽，README已同步；补充既有ADR0025说明退出契约。新类型尚未发布，混合全校/选班或缺角组无法无损恢复，拒绝它们是在落实“分组保存/恢复一致”，旧25类API不受影响。
4. 前端空group按cardIndex区分，后端null视同组，重复预演不一致。**接受统一空group签名**，正常载入生成独立group的逻辑保留。

已审规格、审查证据、六票及main/前端主码；未执行测试，未逐行审交付记录，真实PG和全量由主流程核对。

Spec：4项原始发现；2项代码修复、1项文档已补、1项解释并补ADR；最严重项为草稿无法区分禁排来源。

## 主模型补充

显式SELECTED但缺少classIds与旧scalar classId的临时草稿状态，应保持“待选班级”而不能退为全校。集中修复时一并收紧，并以保存payload和必填警告验收。全部接受项交同一个implementer处理，之后换fresh reviewer。
