# CI 工作日日期假设修正

c37a70b的CI run 41325539556：frontend、deploy-scripts、backend通过。后端1110项0失败，4项手动模型/性能跳过；PostgresContainerIT实际4项0失败0错误0跳过，使用postgres profile。浏览器140通过、1失败，失败位于既有admin-reschedule“已开课实例”场景。

根因由主模型核对源代码与CI日志确定：测试手输过去日期被守卫恢复到今天后，把本周第一张周三课卡一概当过去日期。周一执行时周三属于未来，LessonPicker按日期比较返回可选是正确行为；周日运行却会通过，是测试依赖执行星期。没有排课/调课产品缺陷或需改写的业务规则。

最小修正只改该e2e：在UI部分用page.clock.setFixedTime固定浏览器在种子周五中午，预期日期守卫回到种子周五；周三课明确为过去，保持禁用、合成点击无选中与零写入断言。去掉if(cards.count()>0)可选分支，要求确有一张课卡，避免静默跳过。服务端与此前409/批准/预览/零写入仍使用真实时钟；没有改生产源码。

实际定向运行：scripts/e2e.sh e2e/admin-reschedule.spec.ts -g 已开课，1/1通过，退出0，1.7分钟。浏览器单场景13.9秒。最终整体门禁须在修正后的冻结版本重新执行，当前06因新发现重开，不能拿之前2c2e52f全量绿冒充新版本全量绿。

CI原始证据：https://github.com/xue135792468-gif/paiban/actions/runs/41325539556 。
