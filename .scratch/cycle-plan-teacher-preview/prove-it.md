# prove-it：教师课表预览功能证据

生成时间：2026-11-15。对象：分支 `codex/cycle-teacher-preview`，提交 `fd56ba9`，产品源码在 `0502c13` 后未再改动。

## 做了什么

联动方案卡上的教师姓名可打开只读的完整周课表预览；桌面并排、手机全屏、按教师 ID 切换、应用后/当前安排切换；应用链路仍是原确认与真实接口。

## 测试

- 冻结完整门禁 `verify-run.sh cycle-teacher-preview-ci-fix --full` → 退出 0：前端 107 文件 / 826 项、后端 1265 项（0 失败 / 0 错误 / 7 跳过）、Playwright 156 项通过；源码前后一致、收据有效。
- PR #32 最终 run `43683100205` → backend / frontend / e2e / deploy-scripts 四项通过；首轮及后续 CI 的真实 PostgreSQL `PostgresContainerIT` 已通过。
- 补充真实浏览器证据：`scripts/e2e.sh e2e/draft-drag.spec.ts -g "临时证据采集"` → **2 passed / 0 failed / 退出 0**。
  - 场景 1：方案列表 → 点教师姓名 → 默认应用后 → 切当前安排 → 换教师 → 桌面关闭 → 390×844 手机列表与全屏预览 → 返回；断言预览零写请求、控制台错误 0、页面异常 0。
  - 场景 2：锁定方案内一课后，旧方案 apply 被服务端 409 拒绝、页面刷新草稿；再次打开预览断言整组不投影、禁用「应用后预览」、草稿仅因显式锁定增加一次版本、预览零写请求。
- 补充验收脚本是临时证据采集，运行后已从工作树撤回；正式源码/测试仍为已通过 CI 的版本。首次异常脚本因未刷新父组件草稿而失败，原始日志保留；按产品已有刷新路径修正后 2/2 通过，未放宽断言、未改产品。

## 截图

目录：[.scratch/cycle-plan-teacher-preview/acceptance](acceptance/)

| 文件 | 用户可见状态 |
|---|---|
| `cycle-preview-01-plan-list.png` | 联动方案列表，教师姓名为可点链接 |
| `cycle-preview-02-desktop-after.png` | 桌面并排、默认应用后、尚未生效提示 |
| `cycle-preview-03-current.png` | 当前安排与移动后位置说明 |
| `cycle-preview-04-other-teacher.png` | 按教师 ID 切换另一位教师 |
| `cycle-preview-05-mobile-list.png` | 390px 方案列表 |
| `cycle-preview-06-mobile-preview.png` | 390px 全屏预览与返回 |
| `cycle-preview-07-locked.png` | 锁定后整组不投影、应用后选项禁用 |

备用 worker 逐图复看六项主要界面及锁定态：无遮挡、重叠或不可读内容，**0 个阻塞问题，同意上线**。桌面/手机课表横向可滚动，属于既有设计；用户真实手机滚动手感与视觉偏好仍留给人验收。

## 没验证到的

- 手机真机、真实触摸滚动手感、不同手机浏览器；390×844 Chromium 视口不能代替。
- 学校真实钉钉租户、H5 实机、通知实际送达（属 main 中另一批历史改动，不属于本票预览）。
- 生产数据库 V60–V63 迁移在部署前另以生产副本的真实 PostgreSQL 临时库演练；不把 H2/CI 当作生产库迁移证明。
- 静态截图不证明点击手感；点击行为由上述 Playwright 交互断言证明。
