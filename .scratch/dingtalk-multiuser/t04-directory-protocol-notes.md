# 04 目录响应完整性补充

官方[查询部门用户完整信息](https://developer.alibaba.com/docs/api.htm?apiId=52238)将 `has_more` 列为 Boolean、`userid` 列为 String，分页说明以 `has_more=false` 表示结束（2026-11-11查阅响应字段及JSON示例）。

**本项目的保守策略**：分页标志缺失/类型错误、成员行缺少可识别UID或子部门行缺少ID，均无法确认扫描完整；不能跳过记录后当作完整空目录。这是为账号停用采取的安全决策，不把字段缺失猜成false。合法空数组与明确的false结束标志仍通过。

主模型读到原客户端会静默忽略这些行后，在真实HTTP adapter seam新增8个参数化坏响应场景。首次定向 `HttpDingTalkClientTest`：13条中8失败、0错误，退出1（原5条仍过）；最小修复后同类13条全部通过，退出0。日志为 `logs/t04-malformed-rows-red.log` 与 `logs/t04-malformed-rows-green.log`，计数不与A切片原5条相加。

HTTP请求由本地stub响应，真实客户端承担解析；未请求真实钉钉租户。目录加载器沿用已有结构不完整错误分类，确认阶段通过完整性标志阻止停用。邮箱补齐继续留在原范围之外，本次只修正识别扫描范围所必需的字段。
