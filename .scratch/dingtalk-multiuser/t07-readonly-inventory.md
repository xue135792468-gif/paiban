# 票 07 只读盘点（主线程复核）

2026-11-11，当前集成 3bc1c2f。只读准备，07 尚未施工，未运行测试或访问真实企业。原始 Gemini 报告部分行号漂移，以下由主线程按实际方法定位复核；正式方案以 t07-probe-plan.md 为准。

- IntegrationController.config:78 返回 DingTalkConfigDto（IntegrationDtos:9），只给 hasSecret、不返回明文；update:98 保存参数，空白 secret 不覆盖。当前逐键保存且后校验策略，原子性修复已列入 t07-probe-plan。
- SettingsService.all:224 排除集成秘密；现有接口权限仍按 integration:manage，不能为 probe 绕过。
- HttpDingTalkClient.accessToken:227 已使用 appKey+appSecret 缓存键，实际调用 POST /v1.0/oauth2/accessToken。凭据变化会自然失效；不能虚称已有缓存不失效缺陷。probe 应使用本次独立凭据快照，不写全局运行缓存。
- HttpDingTalkClient.listSubDepartments:117 调用 listsub；它读取下级部门，不是根部门自身详情。Gemini 建议用 listsub(1) 代替根节点验证未被采纳。按既定方案读取 /topapi/v2/department/get 的 dept_id=1，协议主源见 t07-probe-plan。
- SettingsView:37 整卡已有 canManageIntegration 条件，:221 从 integration:manage 推导；:275/:377 处理保存与加载分支。07应保留并补实际权限回归，不声称此可见性从零实现。
- 尚无测试连接 API、独立临时凭据 DTO、按钮与测试结果交互；DingtalkIntegrationActions 是前端入口。
- 保存前测试使用本次输入 secret；显式复用旧 secret 仅在 appKey 相同且已有 secret 时成立。不同 appKey 留空应要求新 secret。无需启用集成、无需先保存。
- 可复用 FakeDingTalkClient、HttpDingTalkClientTest 本地 HTTP stub、DingTalkIntegrationTest 公共接口测试夹具。新 probe 成功只能证明获取 token 与读取根部门，不证明 H5/消息/全部可见范围已验收。

资源快照为盘点当时记录，不作为施工时资源保证。启动后续子任务仍须重新检查内存、端口和磁盘。所有 UI 图示采用本系统真实截图及标明性质的流程图，不伪造第三方控制台截图。