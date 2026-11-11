# 06 外发协议主源（准备，未施工）

2026-11-11，主线程实际检索并打开[阿里巴巴官方asyncsend_v2文档](https://developer.alibaba.com/docs/api.htm?apiId=37692)，可读正文，不是仅SPA骨架。

- 文档列出数字errcode与task_id，前者零表示成功，后者是异步任务标识。HTTP成功本身不足以证明业务受理；task_id也不证明老师已读/实际送达。
- 文档的卡片字段是 `msg.action_card`，内容为 `title`、`markdown`、`single_title`、`single_url`；示例SDK调用为asyncsend_v2。[同一主源](https://developer.alibaba.com/docs/api.htm?apiId=37692)
- 当前工程却组装 `actionCard`、`text`、`singleTitle`、`singleURL`。不能假定机器人卡片形状等同工作通知。06应按上述文档形状补真实HTTP stub请求断言，避免Fake掩盖协议不匹配。文档也给form curl示例；若保持JSON transport，不能据该示例宣称已验证真实JSON兼容。
- 主源未给成功字段的严格必填/正数保证，不能猜task_id必须>0或猜具体错误码。最小策略：明确数字零且响应可解析才视为受理，其余失败安全返回；未知vendor文本不透传。

## 子代理结果的采用边界

Gemini只读研究找到了SPA页面、不可用的SDK路径，并报告一次无凭据空网关请求HTTP200且errcode非0；该网关调用偏离仅文档/SDK的研究范围，不能拿单次空请求推广为“所有错误固定200”，也不能证明成功请求body。未提供租户凭据、未发实际通知；不再重复网关探测。主线程另查到上述可读官方文档，后续以这份证据为主，SDK假路径和过度推断不采纳。

本文件不是06实现、真实企业接入或手机送达验收。
