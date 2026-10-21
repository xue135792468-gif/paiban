# 02: 空状态引导

**What to build:** 实现统一的空状态引导组件：所有列表页/详情页在无数据时显示提示文字 + 操作按钮 + 帮助链接。完成后，教务管理员打开"班级管理"看到"还没有班级数据，点击这里创建"的提示，点击按钮直接跳转到创建页面，点击链接打开帮助文档。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `EmptyStateGuide` 组件：props 接收 title、description、buttonText、buttonAction、helpLink
- [ ] 统一样式：图标 + 标题 + 描述 + 按钮 + 链接，居中显示
- [ ] 覆盖页面：
  - 基础数据：年级/班级/教师/教室/课程列表页
  - 排课：授课任务列表、约束配置、排课任务列表
  - 调代：调代申请列表、审批列表
  - 课表：课表查看页（无发布课表时）
- [ ] 每个页面的空状态文案与跳转目标配置化
- [ ] Playwright E2E 测试：验证无数据时显示引导、按钮跳转正确、帮助链接打开
