# 03 施工与验收汇总

日期：2026-11-11。集成分支 `codex/dingtalk-multiuser`；本票开工基点 `dd5cba1d382b73c95c6126fa80c7f1d6861a652b`。完整原始评审与统筹取舍见 t03-review.md，详细施工见 t03-http/identity/binding/autoprovision/matching/concurrency/ui/review-fixes/empty-semester-delivery.md。本汇总不替代最终八票全量门禁。

## 实现

- H5企业免登以code换UID、再查姓名，外部请求在账号事务外，坏响应安全拒绝。后端固定原因码，SDK/后端失败有说明页和可刷新密码入口。
- UID优先；唯一未绑定同名候选按完整集合计数，重名/无档案拒绝。已有启用账号稳定复用，只有停用账号则拒绝，无账号才开钉钉免登老师账号，该类账号不设置密码。
- 用户确认保护已落实：仅超管可人工变更UID；关联启用/停用管理账号的教师须超管在教师档案明确首次绑定。教务普通档案编辑仍可用，无人值守同步/事件不绕过。
- 绑定、开账号及各自审计同事务；共享账号变更锚点、教师排序锁与刷新防止旧快照竞态、同UID重复开号或两UID相互覆盖。
- V62区分密码账号与钉钉免登账号；密码登录/重置拒绝钉钉免登账号，保留已有密码账号；提升为管理员不改变免登类型。旧审计缺字段仍未知。
- 默认老师进入本人课表；非根显式跳转与管理员初始化落点保留。登录模式改变、密码提交、卸载后迟到的认证或初始化导航均取消。
- 无当前学期时本人课表显示明确空态，不再读取依赖当前学期的班级/作息/课表API。

## 实际证据

| 范围 | 已执行结果 | 原始入口 |
|---|---|---|
| 主线程账号/权限安全回归 | 93条、11类，退出0 | logs/t03-autoprovision/t03-security-regression.log |
| H5适配协议/旧HTTP/Loader | 39条，退出0 | t03-http-delivery.md及logs/t03-h5* |
| 首登及拒绝/密码恢复/旧钉钉集成 | 分别8/5/23条通过，有重叠不可相加 | t03-autoprovision-delivery.md |
| 匹配细节/强制旧快照及HTTP并发 | 分别6/4条通过，退出0 | logs/t03-matching、logs/t03-concurrency |
| 数字诊断脱敏 | 红1失败→绿2，退出1→0 | logs/t03-review-fixes |
| 登录与账号相关组件阶段 | 45条、6组通过，lint/类型检查退出0 | t03-ui-delivery.md |
| 无学期空态 | 新用例红1失败/旧3通过→绿4通过；lint/类型检查退出0 | logs/t03-empty-semester |
| 最终真实浏览器 | 2条通过、退出0，3源码文件运行前后哈希相同 | logs/t03-empty-semester-browser.log |

最终浏览器源码为 `5834fb269c78a6cfe0cf444be6a999c392689f37`；集成合并 `876c9d5eb72b9e8e9ddda93e79a48fad769502b3`。主线程对合并后三个运行文件逐一diff确认为相同，未无理由重复整组测试。页面截图已实看，位于 evidence/t03-empty-semester-browser。测试后28303/25303监听0。

曾经完整候选399两条浏览器1过1失败：无学期却请求classes400。原始失败日志与trace保留在 logs/t03-browser-final-failed-no-semester.log 和 evidence/t03-no-semester-failure，不以随后绿例抹去。空态整改worker交付中的旧trace临时路径已被该归档路径替代。

## 复查与环境边界

- 后端及399增量两轴原始数量、级别与取舍分别保存在t03-review.md；最终空态增量Standards硬违规0/新增判断0，Spec硬违规0/判断2（P3，既有边缘交互取舍）。主线程补足评审未执行的浏览器与未逐行读完的测试/文档尾部证据后，确认原阻断闭环，03放行，状态implemented，下一票05。
- 本地H2、Fake钉钉与HTTP stub；未验证真实PG、真实企业/手机。旧库完整升级与手机→教务审批→通知整链属于08，最终全量verify尚未运行。
- 本票未推送、未部署。被自动审批策略拒绝清理的临时目录及关联worktree保留，见issues/09-temporary-cache-cleanup.md；不能声称所有临时文件已清空。
