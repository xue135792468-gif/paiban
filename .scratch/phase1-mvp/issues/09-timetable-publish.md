# 09: 课表发布与基线只读

**What to build:** 实现课表发布流程与多视角查看：草稿 → 发布动作、发布后 `timetable_entry` 基线表落库并只读、班级/教师/教室/全校四个视角的课表查询接口与 UI、发布后触发钉钉/系统内通知（通知内容本票不实现，仅留接口）。完成后，admin 发布课表后，teacher 登录能看到自己的课表，head_teacher 能看到本班课表，admin 能切换查看全校总表。

**Blocked by:** 08

**Status:** done

- [ ] 发布动作：草稿 ID → 校验无硬冲突 → 写入 timetable_entry 基线表 → 标记学期当前课表
- [ ] 基线只读：发布后 timetable_entry 不允许直接修改（ADR-0008），后续变更走调代流程
- [ ] GET `/api/timetable/class/{classId}` 班级课表（合并视图，默认合并调代，支持 `view=baseline` 切原始）
- [ ] GET `/api/timetable/teacher/{teacherId}` 教师课表
- [ ] GET `/api/timetable/room/{roomId}` 教室占用表
- [ ] GET `/api/timetable/school` 全校总表（一次返回所有班级数据，前端虚拟滚动渲染）
- [ ] 四个视角的 Vue 页面，复用 08 票的课表矩阵组件但只读
- [ ] 权限控制：teacher 只能看自己课表，head_teacher 能看自己 + 本班，admin 能看全部
- [ ] 发布时触发通知接口（本票只留接口与日志，通知实现在 12 票）
- [ ] REST Assured 集成测试：发布 → 各视角查询 → 权限校验
- [ ] 性能验证：全校总表加载 < 2s（ADR-0017）
