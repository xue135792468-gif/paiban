# 2026-11-15 生产部署回执

## 发布范围

用户确认整包部署。PR [#32](https://github.com/xue135792468-gif/paiban/pull/32) 已合并为 `main` 的 `7823e46d47de13a8f58e53e3cbc4ebacd2ce3869`，服务器 `~/paiban` `git pull --ff-only` 后 HEAD 与 `origin/main` 同为 `7823e46d`。

此发布不仅包含联动方案教师课表预览，也包含此前已合入 main、尚未上线的钉钉多用户与验证工具改动；生产 Flyway 从 **59 → 63**。

## 前置证明

- PR #32 最终 run `43723158306`：backend / frontend / e2e / deploy-scripts 四 job 全绿，真实 PostgreSQL `PostgresContainerIT` 已通过。
- 本票 `prove-it` 证据：真实 Chromium 逐步截图 7 张、正常路径与锁定异常路径 2/2 通过、零写请求、控制台零错误；见 `prove-it.md` 与 `acceptance/`。
- 部署前生产库备份：`~/backups/paiban/paiban-pre-20261115T143654Z.sql.gz`（157257 B，`gzip -t` 通过）。
- 用该备份在服务器临时库 `paiban_migtest_20261115T144304Z` 以真实 PostgreSQL 回放 V1–V63：59 → 63，V60–V63 全部 `success=true`，失败 0；`timetable_entry / draft_lesson / substitution / teacher` 演练前后均为 `1092 / 2730 / 0 / 162`，临时库已清理，生产库演练后仍为 59。

## 部署操作

1. 后台构建 `backend` 与 `frontend`：日志出现 `Image paiban-backend Built`、`Image paiban-frontend Built`。首次 SSH 被服务器 sshd 掐断，但构建继续完成；随后分次轮询确认，不重复构建。
2. `docker compose -f docker-compose.prod.yml -f docker-compose.override.yml up -d`：backend / frontend 重建，四个 paiban 容器均 healthy。
3. `up -d` 后 Flyway 在生产库应用 V60–V63。

## 线上验收

| 检查 | 实测 |
|---|---|
| 部署版本 | `HEAD=origin/main=7823e46d` |
| Flyway | 63；`60:true,61:true,62:true,63:true`；失败 0 |
| 角色 | `admin,super_admin,teacher` |
| 账号登录类型 | `PASSWORD:7` |
| 新增列 | audit_log 4、app_user 1、substitution_request 3 |
| 生产数据 | `timetable_entry 1092 / draft_lesson 2730 / substitution 0 / teacher 162 / app_user 7`，与部署前一致 |
| 容器 | postgres / backend / frontend / nginx 四个 healthy；nginx 仍映射 80/443 |
| 服务器内 HTTPS | `https://localhost/api/health` = 200 |
| 公网 | `https://paiban.xhyzzz.dpdns.org/api/health` = 200，首页 = 200 |
| 前端资源 | bundle 内检出「完整周课表」「应用后预览」「点教师姓名先看这份课表」 |
| 部署后快照 | `bash ~/ops/state-snapshot.sh` 退出 0 |

`BACKEND_MIGRATION_ASSETS=0` 是回执脚本查询 Spring Boot jar 内路径的探测写法不适用；不作为检查通过项。实际迁移成功以生产 `flyway_schema_history` V60–V63 四条 `success=true`、健康与实体字段为准。

## 回滚提示

备份 `~/backups/paiban/paiban-pre-20261115T143654Z.sql.gz` 为 V59 数据库快照。V60–V63 是有数据结构变更的迁移；若需跨版本回退，先核对 `flyway_schema_history` 并按跑册决策，不能只 `git reset --hard` 代码后忽略数据库结构差异。应用镜像回滚不加 `--build`。

## 未覆盖

- 学校真实钉钉租户、H5 实机、通知实际送达（属本批历史改动的外部验收）。
- 手机真机触摸滚动与最终观感；桌面/390px Chromium 截图与交互已通过。
- 生产环境人工从联动方案卡点教师姓名的点击体验；线上 bundle 新文案与全部自动交互测试已通过，建议用户从草稿编辑器实操一次。
