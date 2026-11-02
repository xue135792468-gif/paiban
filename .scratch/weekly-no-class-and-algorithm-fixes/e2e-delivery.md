# 每周留空浏览器验收

目标源码89f3eef，另仅修正本文件对应E2E脚本的ElementPlus单选操作：点击可见文字标签，避免被样式覆盖的原生input拦截。三个场景均通过真实页面与业务接口操作，无mock业务后端。

首轮第一场景因测试脚本对radio input执行check超时失败；第二场景开始后，主模型根据trace定位重复点击拦截，主动结束该轮，不声称剩余场景通过。仅终止本次Playwright runner及其已核对的子进程（浏览器、Maven应用、Vite），隔离端口清空后重跑。

重跑：`PAIBAN_E2E_SCREENSHOTS=E:/排课系统/.worktrees/weekly-integration/.scratch/weekly-no-class-and-algorithm-fixes/acceptance MAVEN_OPTS=-Xmx768m bash scripts/e2e.sh e2e/constraints-weekly-no-class.spec.ts`。

实际exit0，3 passed，1.8分钟：

1. 单班保存/刷新、排课保留4课时、灰色禁排、拖入拒绝、直接移动接口拒绝、合法课表发布。
2. 两个指定班级刷新不丢范围、不误伤第三班；改全校后新建第四班并增加任务，重新排课16课时保留，四班都覆盖。
3. 40位置，同班两门课20+19=39，留空2位置：页面未保存预览与保存/刷新均提示39/38/差1，配置可保存，提交排课400。

截图：acceptance/weekly-no-class-single.png、weekly-no-class-scope.png、weekly-no-class-capacity.png。环境为隔离H2，绝非真实PostgreSQL或生产验收。仍待两轴复审及完整闸门。
