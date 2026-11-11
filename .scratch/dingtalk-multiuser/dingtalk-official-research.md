# 钉钉接入：主源核验与实施边界

日期：2026-11-11。仅准备03/07所需资料，不代表已在真钉钉验收。最初Gemini结果只有旧开发者文档摘要，主模型补读了官方组织的示例源码；以下区分证据与工程选择。

## 已直接读到的官方主源

1. [钉钉官方H5免登示例 README](https://github.com/open-dingtalk/h5app-auth-demo)：描述H5微应用免登链、成员信息读权限、应用首页/PC首页配置、CorpId与应用凭据位置以及版本发布流程。是示例工作流，不承诺当前控制台按钮位置完全不变。
2. [官方示例 UrlConstant.java](https://github.com/open-dingtalk/h5app-auth-demo/blob/master/backend/src/main/java/com/aliyun/dingtalk/constant/UrlConstant.java)，Git blob `0f06fc9094400fb17d688f1e62bbb2d0647cabfd`：明确列出 `/topapi/v2/user/getuserinfo` 与 `/topapi/v2/user/get`；示例获取企业token仍用旧 `/gettoken`。
3. [官方示例 DingTalkUserService.java](https://github.com/open-dingtalk/h5app-auth-demo/blob/master/backend/src/main/java/com/aliyun/dingtalk/service/DingTalkUserService.java)，blob `de0b38cd02473b8de8e1633f06906fbc8418c693`：用SDK `setCode(authCode)` 换身份，成功后从result取userid；再用 `setUserid(userId)`、`setLanguage("zh_CN")` 查询用户详情。不是OAuth扫码登录流程。
4. [官方示例 frontend/src/App.js](https://github.com/open-dingtalk/h5app-auth-demo/blob/master/frontend/src/App.js)，blob `82a96c63bdae89ce8eba121dc0922b51b294efd8`：在dd.ready后通过 `dd.runtime.permission.requestAuthCode` 携CorpId取code，后端返回用户详情后页面显示name。与本项目现有前端SDK调用形状相同。
5. [官方JSAPI Explorer requestAuthCode](https://open.dingtalk.com/tools/explorer/jsapi?id=11723)：当前公开正文另展示顶层dd.requestAuthCode，参数为clientId和corpId。它是另一个接口形状，不能据此认为现有runtime.permission调用已失效，也不应未核对就混改两种协议。
6. [官方用户详情文档入口](https://open.dingtalk.com/document/orgapp-server/query-user-details)：主模型确认标题与URL存在，但网页正文为动态加载，当前web读取器返回0行；具体字段实现证据主要来自上述官方示例与SDK使用，不假称已读完整字段表。

## 本项目源码事实

- `integration/dingtalk/HttpDingTalkClient.java` 的现有exchangeAuthCode用POST `/v1.0/oauth/users/auth`、header `x-acs-dingtalk-access-token`、body authCode，读取根userId。公开检索未找到该精确路径的可靠官方字段规范；这不等于证明它不存在，但不能继续假定其有效或包含name。
- 当前企业token通过POST `/v1.0/oauth2/accessToken`（appKey/appSecret）取得；部门/用户分页与工作通知走oapi topapi路径。token与目录路径属于既有代码和已记录的目录同步行为，不因官方旧示例使用gettoken就回退成旧token方案。
- 当前DingTalkClient端口只提供exchangeAuthCode、listSubDepartments、listUsers与sendWorkNotification，没有获取单个用户资料的方法。

## 统筹工程选择（待03/07实际实现及复核）

- 03优先采用官方H5示例可核对的免登码换userid协议，再查询用户详情以取得姓名；沿用本项目企业token缓存方案与现有前端SDK接口形状，不顺便加入扫码/OAuth授权登录。
- 原 `/v1.0/oauth/users/auth` 不作为已验证协议。HTTP适配必须用本地stub固定实际路径、请求字段、响应解析、失败脱敏与超时，假客户端整链不能替代真实适配协议测试。
- 只有vendor验证得到的userid/name可参与配对，不能接受前端自报姓名作为身份依据；配对与开号仍在独立短事务中。
- 07“测试连接”使用表单当前临时参数换token，再读取根部门相关接口；不得污染已保存配置或全局token缓存。不要为测试连接遍历整棵部门树。
- 错误分类只承诺凭据/权限/网络等可读类别。此前摘要中出现的具体88、40014等错误码含义未有可靠主源证明，已删除，后续不可照抄。
- 新HTTP请求需有连接/读取上限，返回/日志/异常/审计不含AppSecret或access token明文；不复制官方demo的printStackTrace和原始异常回传。

## 指南与验收边界

- 说明书使用“建立企业内部H5微应用→填写应用首页/PC首页→开通成员/通讯录与工作通知所需权限→设置可见范围→版本发布→系统测试连接/预演同步→老师实机验收”的工作流；当前后台名称以学校实际界面为准。
- 旧developers.dingtalk.com候选URL在本工具中不可访问，不能把这些URL的摘要当正文证据。
- 本次未登录钉钉后台、未调用带凭证的真实API、未改动生产数据。真实学校权限范围、HTTPS入口、老师手机免登与实际通知仍需人工清单验收。
