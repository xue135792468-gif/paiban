# 06: E2E、术语与帮助文档

**What to build:** 补齐端到端验收与文档：Playwright 覆盖同班换位、跨班换人、撤销、冲突拒绝；`GLOSSARY.md` 增加换课/同班换位/跨班换人/相关老师；帮助中心增加「手工调课」场景文档。完成后，新用户能从帮助中心学会两种换课，CI 能守住关键路径。

**Blocked by:** 05

**Status:** done

- [x] `frontend/e2e/swap.spec.ts`：同班换位后两张课表正确
- [x] `frontend/e2e/swap.spec.ts`：跨班换人后班级课表节次/课程不变、教师变化
- [x] `frontend/e2e/swap.spec.ts`：撤销后逐格回到操作前
- [x] `frontend/e2e/swap.spec.ts`：硬冲突被拒绝且提示可读
- [x] `GLOSSARY.md` 增加四个术语（规格阶段已完成）
- [x] 帮助中心「手工调课」文档 + 搜索索引
- [x] `README.md` 功能清单补一句手工调课
