# 2026-11-02 合并与生产部署回执

状态：已上线。用户在开发验收完成后明确要求“进行合并和部署”。本回执覆盖此次后续授权，先前文档中的“未部署”为开发阶段快照。

## 版本与验证

- [PR #21](https://github.com/xue135792468-gif/paiban/pull/21)已合并main，应用镜像构建自`d76709c72b7545b4fc04541b8bbeec7493f623d9`。
- 合并提交与已验收PR头`9866785a619182161aa9fe6b77dabf9b772cd26c`完整tree相同：`34bbe1cc85cd427eee720f3250c300853721a5ab`，没有夹带其他代码。
- PR门禁[41331285307](https://github.com/xue135792468-gif/paiban/actions/runs/41331285307)四job通过，真实PostgresContainerIT实际执行无跳过；本地完整门禁版本/统计见[acceptance.md](acceptance.md)。
- 合并后的main CI：[41366293444](https://github.com/xue135792468-gif/paiban/actions/runs/41366293444)。backend、frontend、deploy-scripts通过；e2e为140通过1失败，旧工作台320px布局横向溢出4px（要求≤1）。失败截图/trace已保留用于独立复核，不称为新算法失败或隐去红灯。后续部署文档提交不改业务源码，同源树CI结果以main最新检查为准。
- 后续部署记录提交只改文档，不重建业务镜像；服务器检出同步origin/main，业务源码与镜像构建版本一致。

## 备份与回退材料

- 数据库备份：`/home/ubuntu/backups/paiban/paiban-pre-weekly-20261102T001615Z.sql.gz`，129812 bytes；非空、gzip校验通过。
- SHA-256：`350406692e956271f3045f90fe872ff568386b4324213e6c1312cf51ab7c1551`。
- 构建前保留旧镜像：`paiban-backend:rollback-weekly-20261102T001615Z`、`paiban-frontend:rollback-weekly-20261102T001615Z`。
- 旧backend镜像：`sha256:42977d7829da5f09f2bd4b2ae00b6cb27b25c30ffe22fa413e2628fb1d381996`；旧frontend：`sha256:aed320755d8824ece894b0101c0f118577d99c584e541795e759c8070627160c`。
- 如需回退，先核对当前新增规则与用户操作，再将已保留镜像重新标为paiban-backend:latest/paiban-frontend:latest并执行compose up -d --no-build。不要重新构建旧镜像来冒充回退。旧应用不执行新增禁排规则；数据库备份继续保留。本次未执行回退或数据恢复。

## 实际部署和保护

遵循部署跑册，保留服务器原有docker-compose.override.yml，不打印或改写环境机密。低内存服务器串行构建backend再frontend，setsid/nohup后台运行，两个build及compose up退出均为0。

- 新backend镜像：`sha256:b08224ada0e2827e7c8c7e701f0673c5fb1efb6945a80dfd7d1bf4142a19ef6f`。
- 新frontend镜像：`sha256:102943e41ff96f5bc353448b61ded5a502d81931e17205b5dadcf29c6fcedee7`。
- postgres/backend/frontend/nginx四容器全部healthy。
- 本机HTTPS `https://127.0.0.1:18443/api/health`与公网`https://paiban.xhyzzz.dpdns.org/api/health`均200。
- Flyway仍V44，无新增DDL；当前学期仍1，正式课表条目1092、草稿2份。
- 部署前后正式课表、constraint_config、constraint_entry整表内容指纹一致：`280bf823d1279125c01711bac325f7a1`、`47adff8ae25830611d69340a827c9e18`、`d41d8cd98f00b204e9800998ecf8427e`。没有新增禁排配置、发布/删除草稿或改写正式课表。

## 生产页面只读冒烟

管理员登录后打开约束页，后端目录实际返回26类，CLASS_UNAVAILABLE的hardOnly=true。浏览器确认“每周不上课时段”“必须留空”，没有该类型的软选项，既有启用规则正常回显。没有点击保存、启用或添加条目；未在生产制造测试学期/课程。

初次页面沿用浏览器旧HTML缓存，旧JS曾错误展示该新类型为普通软硬规则。定位后确认源站/public HTML与容器均已更新，禁用本任务页面缓存重新导航后新JS正常；Cloudflare为DYNAMIC。可用[带发布标记的入口](https://paiban.xhyzzz.dpdns.org/admin/constraints?release=d76709c)，旧页面可按Ctrl+F5刷新。没有为处理缓存修改业务代码或清除用户整个浏览器缓存。

生产业务全流程的写入测试仍以隔离验收与CI为证；此次生产冒烟仅验证真实入口、接口、健康和原数据保护，不把只读冒烟说成生产完整排课试验。

## 留存与收口

服务器保留本次备份、回退镜像、`/home/ubuntu/ops/paiban-weekly-20261102T001615Z-{build,up}.log/.exit`及前后数据指纹作为操作证据。临时执行脚本与本地浏览器轮次文件在任务结束时清理；Ego任务空间已结束。主仓库原有17张修改截图与其他原型未暂存、未删除。


## 发布后验收稳定性收口

合并后发现的手机日期弹层退场测量及代课切周提前取数，已以仅测试等待条件的8953961修复，并经[CI41370590575](https://github.com/xue135792468-gif/paiban/actions/runs/41370590575)整套通过。详细红绿证据见[release-gate](../acceptance-stability/release-gate.md)，两张后续票已关闭。生产业务源码与d76709c一致，镜像不重建；服务器仅同步测试/文档提交，继续健康。
