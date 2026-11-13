# 07 后端切片与主控收口

2026-11-13，worktree dingtalk-t07-backend，分支codex/dingtalk-t07-backend。worker Sagan完成主体，主控在其关闭且无Java进程后接手最终编译收口；不是worker已通过的交付。开工877dee9，测试前已fast-forward合入集成e7d8f34，保留全部原代码与失败记录。

## 行为与边界

- 新增INTEGRATION_MANAGE保护的POST /api/integrations/dingtalk/test-connection，按t07-wire-contract的三个输入字段解析当前凭据；本次非空secret优先，空值仅显式复用、相同保存key且存在保存secret时允许。
- 独立reader用短只读REPEATABLE_READ事务取得保存凭据；返回后结束事务，探测网络不在这个事务内。不使用SettingsService.all（它排除secret且是多次查询），不改运行客户端token缓存。
- 独立真实HTTP适配器每次POST新版token接口（JSON），再POST根部门详情（官方已核表单dept_id=1、本次token）；5秒连接/15秒读取上限。root响应须明确数值errcode=0、dept_id=1，小数0.5/1.5不截断为成功。
- OK/TOKEN_REJECTED/DEPARTMENT_REJECTED/NETWORK_ERROR/INVALID_RESPONSE均固定安全中文，实际外部尝试失败200 false，参数400、权限403。应用日志只写固定阶段和安全代码，不带vendor正文、异常、凭据或带token的URL。
- 保存配置先校验同步策略，再SettingsService.putAll原子写入；保留空secret不覆盖。探测不写配置/审计/账号/教师/通知。
- Fake-only客户端可用alwaysReturnProbe控制结果；另约定secret=fake-probe:CODE供浏览器选择安全失败，不增加HTTP控制端点。默认目录两人及运行auth/directory行为保留。

## 实际证据

最终主控运行：`.runtime/t07-main-final-run2/run.sh`，doctor退出2（无Docker/5432，H2边界）；Maven两类26条，18条HTTP适配器、8条公开HTTP集成，失败/错误/跳过均0，真实host退出0，maven.exit=0。source-before/after.sha256包含递归枚举的全部backend/src、frontend/src、frontend/e2e文件（含未跟踪新文件），source-stability.txt=SOURCE_IDENTICAL=1。运行期间未修改本工作树源码。

协议18条使用MockRestServiceServer验证真实RestClient的请求构造、转换、完整URL、方法、Content-Type、本次凭据/本次token/root参数、拒绝/超时/坏响应及安全日志；这是离线请求适配器证据，不是实际网络或租户回执。集成8条使用真实HTTP与Fake，覆盖未保存、保存不半写、复用所有边界、权限、四种安全失败、Fake浏览器约定、无持久化副作用。

worker早期日志在`.runtime/logs/`：red-01为旧端点404、red-02为非法策略部分写入、red-03为初始适配器未返回OK的行为失败；green-01/02/03/04是各阶段通过，不与最终26重复相加。早期没有原始exit文件的运行不倒填退出码回执。

green-05及retry/retry2、combined-01是RestAssured链式返回类型与Spring matcher导入的测试编译失败，没有执行到新增行为测试；不作为产品红测。主控第一次`.runtime/t07-main-final`同样因新测试缺少Map import编译失败，maven.exit=1、源码哈希一致；加上该import后同一组合在run2转绿。失败日志全部保留，未覆盖为成功。

## 改动与未验证

仅integration/web的Controller/DTO、FakeDingTalkClient、新DingTalkProbeClient/Code/Http适配器、新ProbeService/StoredCredentialReader、两类新测试与本交付记录。真实运行客户端、绑定/账号规则、通知核心、迁移未改。

最终26条不包含主控随后修改于另一个集成工作树的E2E拒绝分支；它不改变本切片后端源。合入后由主控跑真实浏览器、查看截图并进行独立双轴审查。本票尚未放行，最终full verify、真实PostgreSQL/租户/手机/推送/部署均未执行。任务文件和失败证据保留，临时Java已随Maven结束，不删除缓存或绕过票09拒绝。
