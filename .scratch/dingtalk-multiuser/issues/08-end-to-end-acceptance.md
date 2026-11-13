# 08: 整链验收

**What to build:** 一条后端整链集成测试 + 一条 Playwright 端到端测试，证明「钉钉进入 → 提交代课 → 审批 → 通知 → 审计」整条链成立。见 spec「Testing Decisions」。

**Blocked by:** 03, 04, 05, 06, 07

**Status:** in-progress

2026-11-13：07经最终双轴补核放行（b7cac2e），08开始。主控在dingtalk-t08-chain负责后端Fake身份和真实HTTP整链；Galileo在dingtalk-t08-upgrade负责旧库完整迁移演练；Dalton在dingtalk-t08-browser负责手机→电脑→手机E2E。独立写集基于016859b的同一最终审源，交付前合最新集成。完成证据与最后全量门禁尚待产生，不能据任务拆分认作通过。

- [x] 后端整链（假钉钉）：自动开号 → 老师提交 → 教务管理员改人批准 → 申请人与接课老师各一条钉钉通知、其他人无 → 审计与状态历史可查
- [x] Playwright：老师（手机视口）免登进入提交代课；教务管理员（电脑视口）在待审批批准；老师在「我的调代」看到「已生效」
- [x] 升级演练：用旧库跑全部新迁移，`admin` 为超级管理员，业务数据计数不变
- [ ] `scripts/verify.sh --full` 绿；交付记录写入本票

2026-11-13：三个切片均已合入be48fc0，独立接收单2c09fdb；验收入口[t08-main-acceptance.md](../t08-main-acceptance.md)。浏览器worker未产出后由主控接管实际施工和运行。升级为H2演练，不证明真实PG；手机为浏览器仿真，不证明真实租户送达。当前整特性双轴复查与单一fixer收口中，最后完整门禁未跑，保留in-progress，不push、不部署。

同日后续：主控发现原整链免登后用直接URL跳到申请页，掩盖了手机默认课表没有导航入口的问题，已真实红例复现并重开本项，见[whole-mobile-navigation-qc.md](../whole-mobile-navigation-qc.md)。唯一fixer补导航并把原整链改成点击进入；转绿、增量复核及最后完整门禁前不勾选。

2026-11-13 最终专项：手机真实菜单入口已修复并实跑32项通过，见主控验收最新节；原重开项关闭。整合源7413a01，08仅剩完整verify门禁，状态仍in-progress。

首轮full后端唯一测试断言失败已修复并复查；下一完整重跑未验前本票继续in-progress。详见[最终验收](../final-acceptance.md)。
