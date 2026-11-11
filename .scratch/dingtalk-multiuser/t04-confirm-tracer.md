# 04-C2 确认停用纵向 tracer（红例起点，尚未验收）

源码基点 `34103a6fbe0153c646162d5d23b5b6ce7436bf0c`。主线程独立编写一个公开 HTTP tracer，文件 `backend/src/test/java/com/paiban/integration/DingTalkSyncDeactivationIntegrationTest.java`。没有批量提前写未来测试；先让这一条与最小确认事务形成红→绿，再逐个扩展失败边界。

## 已实际执行

2026-11-11 14:01，GitPortable Bash，工作树 `dingtalk-t04-acceptance`，TEMP/TMP/TMPDIR 均指向本树 feature/.tmp。

```sh
source .tools/env.sh
cd backend
./mvnw -B -DforkCount=0 -Dtest=DingTalkSyncDeactivationIntegrationTest test
```

日志重定向到 `logs/t04-confirm-tracer-red.log`，直接保留命令退出码，不使用管道。退出1，Tests run 1 / Failures 1 / Errors 0 / Skipped 0，61秒。

**有效红例**：配置、两位教师/账号创建、登录、确认接口200均已通过；随后从账号查询接口读到选中账号 enabled=true，而规格要求 false。未因编译错误/外部网络/环境缺失失败。未选账号、JWT拒绝、单条同步审计等后续断言尚未到达，不能声称它们已通过。

日志 SHA256 `2307E776D7F695F8E7A7443827F628A22A52E99DF680972FAFD1CE483A870ACE`。测试服务正常关闭，无残留Java进程。doctor 可用内存5GiB、磁盘7.3GiB；本机无Docker/外部PG，测试使用独立H2+假钉钉，不是真钉钉或真实PG。

## 后续C2施工与验收

- 先在A扫描接口和C1统一账号保护合入后接上最小确认路径，让本条真实绿；再按TDD逐个补无选择安全默认、部分扫描拒绝、目录/绑定漂移、批量失败回滚、自停保护、重新启用可免登等。
- 目标不止HTTP200：显式选中者停用、被排除者不变、旧登录下一请求401、教师保留、只一条SYNC_DISABLE且实际管理员为actor。课表保全需用非空发布数据另证，本 tracer 未播种课表，不能用0→0冒充保全。
- 暂不把本红例分支合入集成；C2完成绿例及定向检查后再由merger整合。整票浏览器截图与双轴复核仍待完成。
