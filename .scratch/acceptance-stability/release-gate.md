# 发布后测试稳定性补丁门禁

范围：两个既有E2E等待条件，生产业务源码不变；此补丁不重建/重启已健康的应用镜像，不变更正式课表或约束。

红证据：41366293444与41368794653的320px日历退场时documentDelta4/innerDelta0；41368221373的代课切周提前采样造成5/4差异。原始失败均保留在线CI记录，首个截图已存入workbench-mobile-overflow。

主模型核对trace：Tab完成654577.656，开始测量654626.13，间隔48.474ms，小于日历300ms退场时间。独立UI顾问提出弹层原因，主模型核对依赖固定322px宽与trace回值；修正等待真正隐藏。代课样本4节均同教师，单次cover移走1节，等待3再验证下周4。

正确位置最终补丁本机执行scripts/e2e.sh e2e/workbench-a11y.spec.ts e2e/substitution.spec.ts，3/3通过，退出0，54.8秒。首次等待误加键盘场景虽绿，不计为手机修复证据；主模型发现并移到320场景后重新完整跑上述3项并通过。

fresh只读核对确认：等待位于320场景原Tab之后，原document/inner≤1px与完整后续操作保留；代课原下周+1和标签断言保留。无P级发现。未新增接口或测试接缝，沿用已授权公开页面与API。

本机没有Docker；本补丁仅测试等待，不重复未变业务代码的本地全量Maven。最终同提交CI执行完整backend/frontend/e2e/deploy-scripts并确认真实PostgresContainerIT；通过前两票保持in-progress，不把3项定向当全量。


## 最终CI收口

补丁895396165eeb0c2a033b546e831f9aa13cac4c3e的[CI41370590575](https://github.com/xue135792468-gif/paiban/actions/runs/41370590575)四job全部success，已读取实际job日志核对：后端1110项0失败0错误4项手动/性能跳过，PostgresContainerIT实际4项0失败0错误0跳过；前端61文件465项通过；Playwright141/141通过，两条原失败场景实际执行成功。两票现done。

后续仅文档提交。前端业务src tree仍8f5c1a93ecf39e07d6acce981976e68f1faaa5a0，与生产构建d76709c一致；不重建/重启应用。失败证据和最初错误放置等待的过程均保留，不把第一次错误位置通过当最终修复证据。
