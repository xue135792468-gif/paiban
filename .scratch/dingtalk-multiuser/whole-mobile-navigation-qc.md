# 手机默认落地后的真实入口缺口

2026-11-13，主控补查并复现。原08整链浏览器通过真实认证，但随后用page.goto直接到申请页，未证明手机用户能够从默认落地页找到该入口。之前三张流程截图只验证了当时页面的可见内容，不是全移动端导航审计。

实际源：useLoginLanding.ts令普通老师落到/workbench/timetable；MainLayout.vue在<=900px隐藏aside，header仅帮助与退出；TimetableView/WorkbenchLayout无前往调代的入口。这是本次承诺的手机免登→申请链的验收缺口，需在原08内收口，不新增信息架构或权限规则。

主控新增dingtalk-mobile-navigation.spec.ts，唯一合成身份经真实POST免登为Teacher，断默认课表URL及隐藏aside后，断可见“打开导航”并通过菜单往返课表/我的调代、管理入口不可见。不伪造JWT/成功响应、不靠输入另一个URL完成导航，只停用本例刚创建的账号。

## 真实红例

- 受检源：集成55e20e7加已stage的新spec；产品MainLayout未改。
- 运行：logs/whole-mobile-navigation-red1，独立28518/25518。
- 主控host退出1，1项失败，SOURCE_IDENTICAL=1；认证200、Teacher角色、默认/workbench/timetable与隐藏aside均先通过，唯一失败为getByRole(button,name=打开导航)不存在，等待20秒失败。
- 主控实际view_image看过evidence/whole-mobile-navigation-red1/whole-mobile-navigation-landing.png，画面确实没有手机导航入口。原始trace/test-results与全部日志留在专属.runtime和logs，不删除、不把此红例说成工具错误。

## 有界修复与后续门槛

唯一fixer仍Turing：已绿的第一批H1/J1/J2/J3保留独立提交与R6后端12项证据，不重跑不混入新UI。第二阶段只补窄屏可见导航按钮/抽屉，复用原visibleMenuGroups及其route target；优先共享小菜单组件，桌面aside/testid不变；不改变默认本人课表或任何权限。原08整链也应改为真实点击导航进入申请页。

新阶段先静态，主控跑新红例转绿及受影响浏览器，再做两轴新源增量。真正手机/租户与PG仍未验，全量尚未跑，08当前不放行。
