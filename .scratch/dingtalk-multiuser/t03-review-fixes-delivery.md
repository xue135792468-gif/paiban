# 票03 复查整改交付：失败登录保留数字诊断（2026-11-11）

分支 `codex/dingtalk-t03-review-fixes`，基于 `3bc1c2f`（当时集成），收尾合并 `d2b0c60`。
只做 code-review 已采纳的一条有界整改：免登调用失败时保留两个数字诊断，不扩范围。

## 问题

`DingTalkAuthProvider.authenticate` 只有 `catch (RuntimeException ex)`，统一映射成
`DINGTALK_CALL_FAILED` 后把异常丢掉。真实 `HttpDingTalkClient` 抛出的
`DingTalkApiException` 已经把 vendor 正文和凭据清洗成只含 `errcode`/`sub_code`，
但这条诊断在 provider 里被吞掉，线上只剩「钉钉登录暂时不可用」，没法区分 IP 白名单
（`errcode 88 / sub_code 60020`）、无效 authCode（`40013`）之类。

## 改动（写集 3 个文件）

- `backend/src/main/java/com/paiban/integration/auth/DingTalkAuthProvider.java`
  - 新增 `private static final Logger log = LoggerFactory.getLogger(DingTalkAuthProvider.class)`，沿用项目 SLF4J 惯例。
  - 在旧 `catch (RuntimeException ex)` 之前插入 `catch (DingTalkApiException ex)`：
    `log.warn("钉钉免登外部调用失败：errcode={}，sub_code={}", ex.errorCode(), ex.subCode())`。
  - WARN 只有固定文字 + 两个整数；不记录异常对象、cause、堆栈、`getMessage()`（即使已 redact）、
    vendor 正文、action、UID、凭据。
  - 用户侧不变：仍是 `401` + `DINGTALK_CALL_FAILED` 固定 code/message；未知 `RuntimeException`
    继续走旧的安全映射，不新增日志、不新增响应字段、不写账户审计。
- `backend/src/test/java/com/paiban/integration/DingTalkLoginDiagnosticsIntegrationTest.java`（新独立类）
- 本文件。

## 测试边界（公开 HTTP + 日志）

- 独立 H2（`jdbc:h2:mem:paiban_login_diagnostics`）、`@ActiveProfiles("test")`、`RANDOM_PORT`；
  真实 settings/identity/provisioner 照常装配，只用 `@MockitoBean` 替换第三方 `DingTalkClient`
  （test profile 下默认是 `FakeDingTalkClient`，按类型替换，不动内部服务）。
- `@ExtendWith(OutputCaptureExtension.class)` 观察服务端输出；用真实 `adminToken` 调
  `PUT /api/integrations/dingtalk/config` 打开集成开关（真实 settings 写入，端点本身不调外部客户端）。
- 用例 1：`exchangeAuthCode` 抛 `new DingTalkApiException(action含裸canary, 40013, 42, errmsg含authCode/token/AppSecret/canary)`。
  断言 `POST /api/auth/dingtalk` = 401、`code=DINGTALK_CALL_FAILED`、message 为固定文案；
  响应体不含 canary、不含 `40013`；含固定诊断字的 WARN 行必须同时出现 `errcode=40013` 与 `sub_code=42`；
  整段捕获输出不含 canary、原始 action、`authCode=`、`AppSecret`、`DingTalkApiException`。
- 用例 2：未知 `IllegalStateException` 仍返回 401 + `DINGTALK_CALL_FAILED` 固定文案（旧映射钉住）。
- 不断言 logger 调用次数，不断言内部 repository 交互；admin/fake 的默认种子口令是既有测试数据，不作为 vendor canary。

## 红 → 绿（真实退出码）

| 阶段 | 命令 | 结果 |
|---|---|---|
| 红（旧实现） | `./mvnw -B -DforkCount=0 -Dtest=DingTalkLoginDiagnosticsIntegrationTest test` | **exit 1**，`Tests run: 2, Failures: 1, Errors: 0`；失败在用例 1「找不到可定位的 WARN 诊断行」，用例 2 已绿。编译通过，是功能红 |
| 绿（最小实现） | 同一条命令 | **exit 0**，`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`（47.92s），`BUILD SUCCESS` |

日志原始路径（本树）：`feature/logs/t03-numeric-diag-red.log`、`feature/logs/t03-numeric-diag-green.log`。
绿日志里实际输出行为 `WARN ... c.p.i.auth.DingTalkAuthProvider : 钉钉免登外部调用失败：errcode=40013，sub_code=42`（控制台代码页显示为乱码，内容为上述固定文字 + 两个整数）。

## 执行环境

- GitPortable bash，`source .tools/env.sh`；Maven `-B -DforkCount=0`，单 class，无并行。
- `TEMP`/`TMP`/`TMPDIR` 指向本树 `feature/.tmp`，日志重定向到本树 `feature/logs`，未用系统临时目录。
- 与主线程的真实 E2E / 全量 verify 串行；跑前复核 free（红 2.89 GiB、绿 2.39 GiB，均 ≥2 GiB）。
- 进程全部退出，未留后台服务。

## 边界

- 未跑其它测试类、未跑全量 `verify.sh`、未跑 E2E、未部署、未推送。
- 未处理同一 code-review 里其它 5 个 Standards 判断项；未新增迁移、配置、日志框架、诊断响应字段或账户审计。
- 只有 H2 证据；未连真实钉钉租户。数字诊断来自本地抛出的结构化异常，不代表对真实 vendor 错误码做过实网验证。
