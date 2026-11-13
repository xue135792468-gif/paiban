# 07 主控整合验收（未放行）

2026-11-13。后端候选5ba87a7已独立合入5c91976；整个树相同，后端/前端/E2E的Git diff为空。候选源与主控测试清单差异仅两份既有Java文件的LF/CRLF，merger已按换行变换核实内容相同。合并记录见logs/t07-merge-07。

## 后端

最终组合与上下文回归红绿见t07-backend-delivery.md。主控已核实际退出码、完整源码稳定性和原始统计；旧FakeDingTalkClient净改动为零，探测替身独立，不削弱旧Mockito替换测试。新探测API权限/复用/无持久化副作用与离线HTTP适配器通过。

日志由主控逐文件SHA核对归档：logs/t07-backend-worker（47文件）、t07-main-targeted-run1/2/3及t07-context-red（各8文件，另有sha256清单）。编译失败、旧上下文1错误与修复后的通过分别保留，不把它们混成业务断言红绿。Docker/5432缺失，doctor退出2；没有真实PostgreSQL或企业租户证据。

## 前端主控QC

UI原切片及首次定向见t07-ui-delivery.md，67份运行/源清单已逐文件哈希归档到logs/t07-ui，阶段green08准确为18，最终切片20，不相加。

顾问建议与主控取舍见t07-ui-advisor.md。主控采纳占位符与新key提示一致、清空key不误称新key、成功说明测试不保存、手册补启用动作。新增公开断言在旧组件上真实2失败/7通过，修正后组件与SettingsView组合20通过、退出0；目标eslint与Vue类型检查均退出0。日志/原始退出码在本特性.runtime/t07-ui-qc/logs，运行期间没有继续编辑源码；不是新的功能或权限调整。

## 尚待执行

合入后主控实际Playwright两场景（包括成功、复用/修改失效、受控拒绝和权限/console），实际查看截图并补入docs/手册；随后固定整票源码做Standards/Spec两轴独立code-review。上述完成前07不放行，08仅准备。原八票最终full verify尚未执行，未推送/部署。被拒绝清理按09保留。
