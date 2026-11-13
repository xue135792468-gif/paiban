# 08 主控整链验收

2026-11-13。07最终审源016859b、文档闸门b7cac2e后，08以独立写集启动；全过程未push、未部署。这里只记录已产生的证据，施工分工不是通过证明。

## 后端

主控dingtalk-t08-chain切片已在冻结源码上完成定向验证：`FakeDingTalkAcceptanceIdentityTest,DingTalkAcceptanceChainIntegrationTest,DingTalkIntegrationTest`，共26项（2/1/23），0失败、错误或跳过，退出0，SOURCE_IDENTICAL=1。原始记录见[交付](t08-chain-delivery.md)及logs/t08-chain/chain-run2.*；独立merger已核验合入527ca2f，接收单26edaa6，见[t08-chain-merge-receipt.md](t08-chain-merge-receipt.md)。20份日志/清单文件复制后SHA一致，636个后端源文件与运行清单逐字节相同，规范化后与提交Git对象一致。

新增测试使用独立H2上下文、真实认证POST/JWT与公开审批接口。原教师申请人、期望代课人、最终接课人和教务账号分开；待批不生效不发通知，改人批准后保留期望并按最终人生效，核状态历史/审计主体/实际课表，外发边界与发送记录恰好申请人及最终接课人各一条。Fake新增身份能力只在fake=true的既有组件中，不写同步目录，不改真实HTTP适配器。

失败历史：Fake能力旧实现拒绝新身份，1错误/1通过；首次整链因主控断言误写通道名大小写失败，修测试为既有dingtalk契约后转绿，不把错误测试期望计为产品回归。所有原始日志与退出码保留。

## 待验证

升级首轮观察（未放行）：Galileo的run1共5条中4通过，包含所有种子表的旧列逐值保全；唯一失败为测试要求super_admin拥有permission历史目录所有项。主控实读V60及IdentityDataInitializer确认两条退休按本班权限只保留目录、不再分给任何预置角色，此处是测试期望语义错误。已要求worker按当前活跃权限与退休项分别断言并保留原始失败，不改迁移授权来迎合测试。最终重跑尚待。

- Galileo旧库升级切片已交付20d6706：5项全部通过，21张非空业务表逐列投影保全，授权表有意变更与V61/62/63新增分类/默认/约束/索引分开验证；包括旧substitution_history主体。最终代码604行、SHA256为9AA5AA24ADB1DA37C83C478C329D8C36357C475C115EAEE915478732CDABB119。独立整合仍在进行，不把H2演练称作真实PG。
- Dalton长时间未产出源码，主控确认空写集后关停并接管浏览器工作树；实际代码和两轮浏览器由主控完成。最终fecb656：手机免登真实POST取得JWT→独立电脑教务真实UI改人批准→手机我的调代已生效及最终教师，四组7项通过，退出0，SOURCE_IDENTICAL=1，提交后POST_COMMIT_SOURCE_IDENTICAL=1。原始记录在dingtalk-t08-browser工作树logs/t08-browser-run2，待独立merger复制核验。
- 全特性两轴最终审查及整改收口；目前016859b仅预审，不代表含08的最终评审。
- 收口文档后冻结源，串行一次完整verify --full；通过前08与所有原票不标done。

## 证据边界

主控实际view_image核查run2三阶段图片并复制到手册资产，SHA256逐一相同（尚待主控文档提交）：teacher-phone-submit.png=54008B0778E1268CD45667D124B222331C148475AE3D41D85F6B87C7D792DCD3；academic-desktop-approve.png=F0212F975AA8E5F59CD22428651395B131787CAB6C391EF6BC19E85D4BAAEA0B；teacher-phone-effective.png=4AD0E21FF8BA5AE53E112B882BE1B4A86D3FB5D7143F8829B19D24163F5FDB08。run1功能7项已通过，但最终截图停在上方表单；run2新增scrollIntoViewIfNeeded+toBeInViewport并再次7项通过，同屏看见期望/实际/已生效。不将两轮相加，也不把截图改进称为产品回归修复。

静态首轮因classEntries测试类型省略真实teacherId而tsc退出2，补齐类型后eslint与strict e2e-tsc最终退出0。共享SDK原样从03移入helpers，种子新增teacherAccount:false且默认行为不变，未改产品界面或真实HTTP适配器。视觉顾问仅辅助核图，所谓“实机”已由主控边界校正为离线手机视口；不据其宽泛“准予”代替主控和双轴最终闸门。28518/25518监听0，浏览器工作树标记Java/Node/Chrome进程0，原始trace与缓存保留。

H2/Fake不证明真实PostgreSQL或钉钉租户送达；真实手机/租户清单见学校说明书，尚未执行。已拒绝的缓存/工作树删除按09保留，不换方式重试，只关闭本任务启动的临时进程。

## 2026-11-13 独立整合完成与主控证据校正

三个切片已整合：后端527ca2f；浏览器fa306ea；升级be48fc0。剩余两片接收单2c09fdb，复制的日志/图片逐文件SHA一致，三张选定图已随接收单入库。固定全8票审源为be48fc0，整特性两轴最终审查与单一fixer尚在进行；不是最终放行。

接收单中“run2 HEAD引用陈旧”表述不准确：真实浏览器运行时HEAD就是527ca2f，结束后主控才FF合入纯文档26edaa6并提交fecb656；原始source-refs记录当时HEAD正确。源码清单before/after/postcommit相同，Git对象证明的是所列受检源，不包括后来新增文档或整棵Git树。保留接收单原文，以此处时间线校正。

升级切片没有独立逐轮exit文件或before/after哈希清单，实际退出码来自worker报告；归档日志确有5项通过和BUILD SUCCESS，提交源码哈希一致，mtime与提交关系能辅助对应，但不能据日志正文伪称独立host退出码/源码冻结收据。最终完整门禁会在冻结全部最终源后生成直接退出码与前后Git对象清单，届时才可补强此层证据。旧列捕获实际用ResultSetMetaData（SELECT * WHERE 1=0），不是接收单概述中的information_schema；保全断言本身不受影响。
