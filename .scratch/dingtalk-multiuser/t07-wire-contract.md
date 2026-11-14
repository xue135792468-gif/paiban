# 07 测试连接接口与交互约定（准备，未施工）

2026-11-13 主控决定；必须等 06 双轴放行后才施工。工程与主源边界见 t07-probe-plan.md，不能把本约定当运行证据。

## 请求与结果

- `POST /api/integrations/dingtalk/test-connection`，现有 `Permissions.INTEGRATION_MANAGE`；教务/老师 403，不要求集成 enabled。
- JSON `{appKey, appSecret, useSavedSecret}`。临时凭据不放 URL/query；只在本次内存调用中使用。非空本次 secret 优先；空 secret 仅在显式 useSavedSecret=true、appKey 与保存值一致且保存密钥存在时复用，否则参数错误 400。
- 结果 JSON `{success:boolean, code:string, message:string}`。参数/权限错误沿用现有安全 400/403；已经实际尝试的探测无论成功或外部失败都返回 200，success 表示本次两步是否通过。
- code 至少区分 OK、TOKEN_REJECTED、DEPARTMENT_REJECTED、NETWORK_ERROR、INVALID_RESPONSE。它们描述失败阶段，不把未知 token 拒绝断言成密码错误、未知部门拒绝断言成权限不足。只有已核实主源的供应商码才可额外精分；UI 使用固定中文 message，不直接显示供应商正文或异常。
- 本次独立获取 token，拿本次 token 读根部门 dept_id=1；不读写运行客户端 token 缓存，不写配置/审计/教师/账号/通知。所有网络在事务外且有超时。不增加公开发送通知端点。
- 成功说明明确为“本次凭据可获取访问令牌并读取根部门”；不宣称 CorpId/AgentId、免登、通知、全部可见范围或手机均已验收。

## 表单交互

- 测试按钮与保存分开；使用正在填写的参数，点击不保存，不清空或回填表单，不把未保存状态改为已保存。
- 只有已配置密钥且 appKey 未改时，空 secret 显示可复用保存密钥的说明，并在探测请求明确 useSavedSecret=true。appKey 改变且 secret 为空时要求输入匹配新密钥，不能偷用旧密钥；保存仍遵守既有空白不覆盖语义。
- 探测期间独立 loading 防重复；输入变化后清除上次探测结果或明确标为旧参数结果，不能让绿色成功暗示新参数已通过。
- 组件/浏览器断当前未保存参数、成功/安全失败提示、保存值复用边界、输入后结果失效、无权限隐藏；真实控制台错误记录并保留截图。

只约定本票最小行为，不新增向导或参数验证平台。手册仍需本系统实际截图、流程图、官方链接和学校实机清单。
