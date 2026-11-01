# 06 发布边界、权限与版本验收

2026-11-01，主模型新增WeeklyNoClassPublishedAcceptanceTest，通过公开HTTP操作规则、发布和调课；仅用仓库方法构造确定性导入形状的两节草稿，发布后重新读取正式entry id。

实际2项通过，exit0、BUILD SUCCESS；命令：source .tools/env.sh，backend ./mvnw -B -DforkCount=0 -Dtest=WeeklyNoClassPublishedAcceptanceTest test，MAVEN_OPTS=-Xmx512m。

- 教师保存/预览规则均403，版本未前进；管理员保存后，旧baseVersion保存409，不能覆盖新规则。
- 新增留空后旧草稿两节课程保留，禁排元数据出现；删除规则后元数据清空，原课表始终逐项相等，不修改正式基线。
- 先生成合法两课置换，再新增目标留空，批准缓存方案409，申请仍待审批；重新生成无方案并指出CLASS_UNAVAILABLE，没有调代记录、没有部分修改，正式课表保持原样。

本记录为H2集成结果；最终多班/全校与完整闸门仍待05/06收口，不是生产上线证明。
