# 票01：设置字段权限切片

2026-11-11，基点54977a1，切片分支codex/dingtalk-t01-settings。

SettingsController 的 GET、普通更新响应及 Logo 上传响应按 integration:manage 过滤钉钉字段；无权限混合更新包含任一钉钉字段时，在写入任何字段之前整体403。业务设置仍可读写。此切片不新增角色、不连接第三方。

测试边界为真实HTTP，合成业务设置身份只持有settings:manage；临时身份在每个用例之后清除，Logo文件在finally清理。

命令均在本工作树 source .tools/env.sh 后 cd backend 执行：

`./mvnw -B -DforkCount=0 -Dtest=SettingsPermissionIntegrationTest test`

- 读权限红：1项、1失败，返回出现 integration.dingtalk.enabled 等字段。
- 读权限最小修复后绿：1项、0失败。
- 混合写红：`-Dtest=SettingsPermissionIntegrationTest#unauthorizedMixedUpdateDoesNotWriteAnyField`，1项、1失败，期望403实际200。
- 补写入预检后最终绿：4项、0失败、0错误、0跳过，退出码0。包括GET、普通PUT返回、Logo返回和拒绝混合写且原值不变。

最终原始日志SHA256：224F3797B505B14E69E8F81659575E96C85BA2D07C29E9BCC048BBF8AFB3E654。日志为任务临时文件，记录统计/哈希后清理；未运行真实PostgreSQL。整票01尚需合并角色与前端切片及双轴复查，未宣称整票done。
