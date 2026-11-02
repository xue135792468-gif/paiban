# 01: 320px工作台横向溢出复核

Status: done
Priority: P3

## 已知事实

部署合并版本d76709c的CI run 41366293444，浏览器140通过1失败。frontend/e2e/workbench-a11y.spec.ts:128的320x700场景，在154行调用expectNoHorizontalOverflow，测得documentDelta=4px，要求≤1px；于进入步骤1并选教师、填日期后触发，未进入后续确认步骤。原始截图见mobile-overflow.png，合成测试数据，不含生产课表。

相同业务代码的PR CI41331285307和两轮本地完整门禁均通过此用例。一次布局失败不能径称环境抖动，也不能据此证明排课算法故障。本批未改该工作台布局代码；线上桌面新规则入口及正式数据保护已实际通过，不为这项小屏视觉缺陷回滚已验证的新功能。

## 后续验收

- [x] 读取CI失败截图与trace，独立核对documentDelta=4、innerDelta=0，以及Tab完成到测量相隔48.474ms。
- [x] 核对日期弹层固定322px宽和退场过渡证据；现有证据指向异步弹层测量窗口。未执行字体/文本组合实验，不以此声称已穷举所有小屏布局。
- [x] 在真正的320px场景Tab后等待可见日期弹层数量为0；保留document/inner≤1px和后续确认流程，不改业务布局。
- [x] 定向覆盖完整键盘/小屏确认流程，并经同提交完整CI通过，最终结果见../acceptance-stability/release-gate.md。

## 证据

https://github.com/xue135792468-gif/paiban/actions/runs/41366293444/job/125027043940

失败时桌面产品与真实PostgreSQL测试通过；同源树后续CI另以执行结果为准。该票与每周留空功能的六票分开跟踪。


## 2026-11-02 只读复核

UI顾问读取截图与trace后指出日期选择器的退场过渡。主模型独立核对trace回值：documentDelta=4、innerDelta=0；本地依赖date-picker.scss第16行固定width:322px。顾问从DOM快照确认body下日历popper带退场class，连左右边框总宽324px，在320px视口恰好产生4px；该候选比随机课卡文字更吻合证据。

建议先在Tab后等待日期弹层真正隐藏，再保留原≤1px断言进行定向验证；不要用固定睡眠或放宽阈值替代。该时序修正现已实施，当前票done，本机和最终CI均已通过。原图与首次失败执行保留；旧代码单纯重跑不算修复，本次条件等待补丁与全量CI共同作为收口证据。


2026-11-02实施：仅在320×700场景日期Tab后等待.el-picker__popper:visible数量为0，再进行原有≤1px测量；无固定睡眠、不改生产布局。最终正确位置补丁已读实际diff，3项本机定向通过；完整CI见../acceptance-stability/release-gate.md。
