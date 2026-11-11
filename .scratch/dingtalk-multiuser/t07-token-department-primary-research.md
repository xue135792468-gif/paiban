# 票 07 令牌与根部门主源研究报告

日期：2026-11-11 | 约束：只读调研，07未施工，不跑测试，不读私钥，不调真实企业API。

## 1. 部门详情主源核实 (POST /topapi/v2/department/get)

- **官方文档主源**：https://developer.alibaba.com/docs/api.htm?apiId=52330 （获取日期：2026-11-11，HTTP 200，API名：`dingtalk.oapi.v2.department.get`）
- **协议与请求形状**：
  - 路径：`POST https://oapi.dingtalk.com/topapi/v2/department/get?access_token=ACCESS_TOKEN`
  - 公共参数：`access_token` 为必须 Query 参数。
  - 业务入参（Body JSON / Form-Data）：`dept_id`（Number，必须，主源明确标明「部门id，根部门ID为1」）；`language`（String，可选，默认 zh_CN）。
  - **重要边界**：`listsub`（获取子部门列表）仅返回下级部门，无法获取根部门自身信息；探测根节点必须使用 `/topapi/v2/department/get` 且 `dept_id=1`。
- **返回成功结构**：
  - 根字段：`errcode: 0`, `errmsg: "ok"`, `result: { ... }`
  - `result` 详情字段（已核实）：`dept_id`（Number，根部门为1）、`name`（String，部门名称）、`parent_id`（Number，主源说明「父部门id，根部门为1」）、`outer_dept`（Boolean）等。
- **主源载明错误码与结构**：
  - 结构：`{"errcode": Number, "errmsg": String}`
  - 主源明确列出：`60020`（IP不在白名单中）、`90018`（请求被限流）、`88`（异常示例通用错误）。
  - 官方说明解决方案（60020）：在开发者后台将发起请求的 IP 加入白名单中。

## 2. 内部应用令牌接口 (POST https://api.dingtalk.com/v1.0/oauth2/accessToken)

- **官方文档与状态**：
  - URL：https://open.dingtalk.com/document/orgapp/obtain-orgapp-token （获取日期：2026-11-11，HTTP 200）。
  - **可读性记录**：该页面当前为前端 SPA（Legao 渲染），纯 HTTP GET 下正文主体为空（无内联 API 入参 JSON），按规约记录为 SPA 空正文不可读。
- **官方参考实现与仓库源码边界**：
  - 来源：`open-dingtalk/openapi-demo-java` 及本工程现有客户端（仅源码，不代表真实租户验证）（`HttpDingTalkClient.java:234`）。
  - 入参（JSON Body）：`{"appKey": "...", "appSecret": "..."}`（Content-Type: `application/json`）。
  - 成功字段：`accessToken`（String，访问令牌）、`expireIn`（Number，过期秒数，通常为 7200）。
  - 失败结构：新版开放平台 OAuth2 接口若失败，返回 HTTP 4xx/5xx 及 JSON `{"code": String, "message": String}`，或网关级错误。
  - **推断边界说明**：钉钉新版 v1.0 与旧版 topapi 错误结构存在差异（v1.0 偏向 `code`/`message`，topapi 偏向 `errcode`/`errmsg`），在未解析到新版 SPA 纯静态错误码字典前，不得臆断具体错误码字符串。

## 3. 诊断分类与最小安全错误映射建议

基于主源已证明事实，严禁“将所有 token 错误武断归为密码错”或“将所有部门错误武断归为权限不足”。错误分类建议如下：

1. **IP 白名单拦截（明确已核实）**：
   - 证据：主源明确定义 `errcode == 60020` 代表「IP不在白名单中」。
   - 处理建议：精准提示用户或管理员在钉钉开发者后台配置当前服务器公网出口 IP。
2. **频率与限流（明确已核实）**：
   - 证据：主源明确定义 `errcode == 90018` 代表「请求被限流」。
   - 处理建议：提示请求过频，稍后重试。
3. **凭据错误（区分对待）**：
   - 仅 HTTP 400/401 不足以证明凭据错误；只有已核实的具体认证失败码才可归类为「AppKey/AppSecret 不匹配或凭据无效」。本轮未核实此类码，暂不精确分类。
   - 若返回未知错误或非认证原因，使用固定安全提示「获取令牌失败（未知原因）」。不得向界面、审计或日志透传原始返回、异常消息或响应体；仅保留已验证类型的安全错误码。
4. **根部门读取/权限问题（区分对待）**：
   - 若获取 token 成功但在调用 `/topapi/v2/department/get` 时返回错误：
     - 只有已核实具体错误码对应权限不足时才提示权限问题；本轮未证实具体权限码，不依据 errmsg 文本猜测。
     - 部门不存在或其它未知错误使用固定安全提示「根部门读取失败」，仅可保留类型验证后的数字 errcode，禁止透传 errmsg、响应体或异常原因链。
5. **网络/传输层异常（明确已核实）**：
   - 连接超时、DNS 无法解析、SSL 握手失败等系统网络异常，归类为「网络无法连接至钉钉开放平台服务器」。

## 4. 结论与后续实现注意

- 本报告仅提供最小安全分类参考，不作最终架构或业务最终决策。
- 07 施工时应保持测试连接（probe）使用独立凭据快照，绝不污染全局运行缓存，绝不泄露凭据明文。
