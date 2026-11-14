# 03-A1 H5可信身份HTTP适配

2026-11-11。只完成外部身份Adapter；自动配对/开号、类型与前端是同票其他片，不能据此宣称03整票完成。

## 主源与实现选择

- 再次读取[钉钉官方H5示例服务](https://github.com/open-dingtalk/h5app-auth-demo/blob/master/backend/src/main/java/com/aliyun/dingtalk/service/DingTalkUserService.java)及[路径常量](https://github.com/open-dingtalk/h5app-auth-demo/blob/master/backend/src/main/java/com/aliyun/dingtalk/constant/UrlConstant.java)：免登code换userid后，按userid查询详情，不用旧的未核实OAuth路径。
- [官方用户详情API52243](https://developer.alibaba.com/docs/api.htm?apiId=52243)给出userid/language参数、result.userid/name响应例及POST form curl。本实现新身份调用使用表单参数，企业token也放表单体，避免放进URL。此编码选择未做真租户实测，不把本地stub当真实兼容性证明。
- 企业token获取/凭据变化缓存沿用既有实现；新增`DingTalkClient.getUser(userId)`，HTTP与Fake都实现。HTTP只返回身份需要的userid/name，不从钉钉管理员标志推断本地权限。详情userid必须与免登交换值相同，姓名必须为非空字符串。
- 两个身份端点共用严格状态/result校验，缺失或非数值errcode不默认为成功。外部错误只保留安全数值诊断，不传vendor正文、transport异常或cause。真实客户端配置5秒连接、15秒读取超时；这是单次连接/读取上限，不声称整条多请求流程的绝对SLA。
- 扩接口同步补了DirectoryLoaderTest的4个委托适配器；没有改原14条目录断言。现有通知、目录业务响应逻辑未借此改写。

## 实际红绿与纠错

均使用GitPortable Bash，`source .tools/env.sh`，Maven `-B -DforkCount=0`；TEMP/TMP/TMPDIR为本树feature/.tmp。日志直接重定向，保留真实退出码。

1. `DingTalkH5IdentityHttpTest` 首条code交换：红1失败（期望官方H5路径，实际旧OAuth路径），退出1，`logs/t03-h5-code-red.log`；绿1通过，退出0，`logs/t03-h5-code-green.log`。
2. 扩getUser接口初次遗漏4个测试适配器，`t03-h5-detail-red.log`及误命名的`...detail-green.log`都是**编译失败，不能算功能红或绿**。补齐委托，明确未实现详情的stub后重跑：2条中1运行错误UnsupportedOperationException，退出1，`t03-h5-detail-functional-red.log`；最小详情实现后2通过，退出0，`...functional-green.log`。
3. 错误防护：缺失/异常status、含假凭据的vendor错误、HTTP500正文共4条真实红；总6条中4失败，退出1，`t03-h5-safe-errors-red.log`。严格状态校验与安全异常后总6通过，退出0，`...safe-errors-green.log`。日志里的凭据均是测试假值。
4. 追加既有字段拒绝回归（缺失/数值/空UID，详情UID不符、姓名缺失/数值）后，运行3类：**39通过，0失败/错误/跳过，退出0，39秒**，`logs/t03-h5-targeted-final.log`。组成：身份HTTP12、原目录HTTP13、目录加载14。阶段计数含重复，不能累计相加。SHA256 `1C41564B061D67548151544A0784750FCB0AFE15D9B2A6544C7000BF0539E05D`。

## 边界

未访问真实钉钉、未测真实凭据/学校权限、未部署。尚未实际测真实网络停滞时的15秒行为；源码显式配置上限，失败映射由本地HTTP错误stub验证。未跑Spring整链、UI或全verify；留同票A2/C/B整合后。新增权限能力分类的契约纠正见wire：遵循ADR-0006，C不应仅按角色名判断管理员。
