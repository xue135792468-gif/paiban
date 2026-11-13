# 08 手机到电脑整链浏览器交付

2026-11-13。Dalton子任务长时间未产出代码，主控确认该工作树无改动后关闭worker并接管dingtalk-t08-browser；本切片代码与实际运行均由主控完成。先合已验证后端527ca2f，测试时HEAD固定527ca2f加三个已stage的E2E文件；精确补丁见每轮logs/t08-browser-run*/source-candidate.patch.txt。没有改产品UI或后端。

## 范围

新增dingtalk-acceptance.spec.ts：独立390×844、DingTalk UA、touch/mobile手机context，经假SDK调用真实认证POST得到Teacher的真实JWT；唯一同名未绑定教师自动开号。手机真实表单提交代课→独立1440×1000教务context改为第三位老师批准→手机刷新「我的调代」，期望与最终人分开、已生效、实际课表教师一致。核实际响应与稳定DOM，不伪造JWT/接口成功，不只断toast。两个context均收集console error/pageerror并断为空。

helpers.ts只新增共享DINGTALK_UA与从03原样移来的installFakeWindowDd；seedSchool增加teacherAccount:false选项供本例保留首次登录前无账号，默认仍创建原密码账号；classEntries补齐后端已返回的teacherId类型。03直接调用者改为导入公共SDK，无弱化原断言。其他fixture、默认EMP001/002、真实HTTP适配器未改；只在finally停用本例刚自动创建的合成账号并关集成开关，保留业务历史，不改绑或降级他人账号。

## 实际验证

- 静态首轮：eslint退出0；tsc退出2，因classEntries原类型省略真实响应teacherId，属于测试类型错误。补齐类型后strict tsc通过，未改产品接口来迎合测试。
- eslint-run2/run3与strict e2e-tsc-run2/run3均退出0，检查helpers、新整链、03免登、07连接与05审批直接受影响用例；不声称组件/Vitest运行。
- 浏览器run1：四spec共7通过，退出0，SOURCE_IDENTICAL=1。原图真实保留；主控实际view_image发现最后手机截图只拍上方表单，不能用它证明结果可见。
- 浏览器run2：仅新增最终人和已生效状态scrollIntoViewIfNeeded + toBeInViewport及截图，再跑同四spec共7通过，退出0，SOURCE_IDENTICAL=1。不把两轮相加；运行的是dingtalk-acceptance、dingtalk-login、dingtalk-connection、substitution-approval。
- 主控实际查看手机提交、桌面改人批准、最终手机结果。run2最终图同时可见期望老师、实际最终老师与绿色已生效；表格自身可横向滚动，不据整页截图看不到内滚动区域判产品故障。图片见evidence/t08-browser-run2/t08-phone-submit.png、t08-desktop-change-teacher.png、t08-phone-effective.png。
- 运行结束28518/25518监听数0，按本工作树标记的Java/Node/Chrome进程0。静态/两轮原始日志、host退出码、源前后路径/规范化Git对象哈希和SHA256SUMS保留；原trace在专属.runtime，不删除。

本切片尚待独立整合与全特性双轴终审、最终full verify；没有真实手机/钉钉租户送达、真实PG或生产部署证据。截图为离线Fake演示，不伪装实机。
