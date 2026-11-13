# 07 主控整合验收（双轴整改中，未放行）

> 当前：原双轴已固定64a8885完成，逐项判定见t07-review.md；单一fixer Planck在dingtalk-t07-review-fixes处理公共helper、空白输入、测试凭据集中和本次请求60秒预算。原raw的优先级/数量保留；确认修复及主控受影响浏览器、两轴独立增量未完，07不放行，08仅准备。

2026-11-13。后端候选5ba87a7已独立合入5c91976；整个树相同，后端/前端/E2E的Git diff为空。候选源与主控测试清单差异仅两份既有Java文件的LF/CRLF，merger已按换行变换核实内容相同。合并记录见logs/t07-merge-07。

## 后端

最终组合与上下文回归红绿见t07-backend-delivery.md。主控已核实际退出码、完整源码稳定性和原始统计；旧FakeDingTalkClient净改动为零，探测替身独立，不削弱旧Mockito替换测试。新探测API权限/复用/无持久化副作用与离线HTTP适配器通过。

日志由主控逐文件SHA核对归档：logs/t07-backend-worker（47文件）、t07-main-targeted-run1/2/3及t07-context-red（各8文件，另有sha256清单）。编译失败、旧上下文1错误与修复后的通过分别保留，不把它们混成业务断言红绿。Docker/5432缺失，doctor退出2；没有真实PostgreSQL或企业租户证据。

## 前端主控QC

UI原切片及首次定向见t07-ui-delivery.md，67份运行/源清单已逐文件哈希归档到logs/t07-ui，阶段green08准确为18，最终切片20，不相加。

顾问建议与主控取舍见t07-ui-advisor.md。主控采纳占位符与新key提示一致、清空key不误称新key、成功说明测试不保存、手册补启用动作。新增公开断言在旧组件上真实2失败/7通过，修正后组件与SettingsView组合20通过、退出0；目标eslint与Vue类型检查均退出0。日志/原始退出码在本特性.runtime/t07-ui-qc/logs，运行期间没有继续编辑源码；不是新的功能或权限调整。

## 真实浏览器与手册

主控run1真实Playwright两条通过、退出0、SOURCE_IDENTICAL=1：当前未保存参数/精确成功/保存值未变、保存密钥复用、改key失效与受控失败200 false，另含教务/老师隐藏及接口权限；相关page的console.error/pageerror断言均为空。主控实际查看两图，发现横幅与AgentId行相贴，采纳顾问3，仅增加结果横幅18px间距。

run2针对同一spec再次两条通过、退出0、SOURCE_IDENTICAL=1；不与run1相加成四条最终用例。run2源码为e6c1d27加记录在logs/t07-browser-run2/source-style-patch.txt的class/CSS补丁，业务源码未变；源码路径/哈希前后都保存，不把HEAD记录误称已包含未提交补丁。主控再次实际view_image确认成功与拒绝图的间距、角色、未启用标签、当前未保存输入、限定成功说明和安全拒绝提示均正确。

最终两PNG已逐文件SHA核对复制到docs/runbooks/assets/dingtalk（090D4FA4…与8594737B…），说明书移除待补图文字，保留离线演示标注、官方链接、流程图、启用动作和学校实机清单。截图源在evidence/t07-browser-run2，日志在logs/t07-browser-run1/2，trace在本特性.runtime对应test-results目录。run2结束后28407/25407监听0，集成工作树Java0。

## 下一道闸门

固定整票源码做Standards/Spec两轴独立code-review；确认问题统一修复并增量复核后才07放行，08仍仅准备。原八票最终full verify尚未执行，真实PG/租户/手机未验，未推送/部署。被拒绝清理按09保留。
