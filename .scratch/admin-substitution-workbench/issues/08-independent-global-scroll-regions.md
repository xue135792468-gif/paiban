# 08: 全站导航与内容独立滚动

**What to build:** 调整全站外壳的滚动行为，使固定导航和右侧内容分别在各自区域滚动。菜单项多时导航仍可达；切换到不同菜单页后右侧内容回到顶部，同时保留左侧菜单位置。同页筛选、页签和日期切换不无故回顶，帮助锚点和弹窗内滚动继续正常。

**Blocked by:** None (can start immediately)

**Status:** done (2026-10-29, 集成分支 `feature/admin-substitution-workbench`；门禁后端 1012 / 前端 417 / Playwright 117 全绿)

- [ ] Playwright 使用长菜单和长页面验证导航区与内容区可分别滚动、互不带动。
- [ ] 滚动导航至中段后切换不同菜单，右侧回到页面顶部且左侧位置保持。
- [ ] 同一菜单内修改筛选、页签或日期时，内容滚动位置不因查询状态变化而无条件归顶。
- [ ] 页面内容高度短于或长于视口时，窗口不发生整体共同滚动，顶部栏和导航标识保持可见。
- [ ] 长导航的首尾菜单项均可到达，长内容底部操作项可正常访问。
- [ ] 帮助页锚点可导航至目标内容并保持可见；页内定位不会被全站回顶逻辑覆盖。
- [ ] 弹窗内部长内容可独立滚动，滚动弹窗不意外移动导航或主内容。
- [ ] 不同页面切换正确回顶；同页状态变化保留位置，测试使用可观察滚动结果而非组件内部状态。

## 规格依据

`spec.md`「全站滚动布局」「Testing Decisions」中的滚动验收场景。

## 交付记录（2026-10-29，分支 feature/t08-global-scroll-regions）

**改动范围（只动前端，无后端/迁移/新依赖）：**

- `frontend/src/layouts/MainLayout.vue`：应用外壳从 `min-height: 100vh` 改为 `height: 100vh; overflow: hidden`，整页不再出现窗口滚动条；左侧 aside 收敛内部滚动（logo 标识固定、`el-menu` 占满剩余高度 `overflow-y: auto`），右侧 `el-main` 固定顶栏后独立滚动（`min-height: 0` 防 flex 撑破）。新增对 `route.path` 的 watch：仅当「路径变化」（换页面，含 `/admin/drafts/:id` 这类 param 路径变化）时在 `nextTick` 后把内容区 `scrollTop` 归零；同页筛选/页签/日期等 query 或组件状态变化不触发回顶；左侧菜单滚动位置从不被主动改动，切页后自然保持。为可观察断言补充 `layout-aside` / `layout-menu` / `layout-header` / `layout-logo` / `layout-main` 五个 data-testid。
- `frontend/src/views/HelpView.vue` 及帮助中心整体未动：`/help` 是布局外的顶级路由，自带 sticky 目录与窗口滚动，其锚点定位（`<a id>` + `scrollIntoView`）不受全站回顶逻辑影响，票据要求的「继续可达」由回归测试锁定。
- 新增 `frontend/e2e/global-scroll.spec.ts`（8 条用例），断言只用可观察结果（`scrollTop`、`scrollHeight/clientHeight`、`toBeInViewport`），不断言组件内部状态；长菜单/长内容用真实视口 1280×620（菜单约 20 项自然溢出）+ 公开 API 造 40 教师/40 教室撑出长表格。

**验收清单 → 证据对应：**

1. 长菜单与长页面分别滚动、互不带动 → 用例「导航区与内容区分别滚动，互不带动」（滚内容菜单不动、滚菜单内容不动、来回各一次）＋「长内容页面不出整页滚动条…」中 `menuTop===0/mainTop>0/windowY===0` 断言。
2. 滚动后切菜单：右回顶、左保持 → 用例「切换菜单页后内容回到顶部，左侧菜单滚动位置保持」（菜单滚 150px 后从教师页切教室页，断言 `mainTop===0` 且 `menuTop` 与切页前相等）。
3. 同页筛选/页签/日期不回顶 → 用例「同页筛选不回顶 › 课表页切换视图页签与视角不回顶」（seedSchool+solveAndPublish 后，课表页滚动 15px 基线上切「原始」页签、切「教师」视角，`mainTop` 均保持不变；路径不变因此不触发回顶 watch）。
4. 短/长内容窗口均不整体滚动、顶部栏与导航标识可见 → 用例「长内容页面…」（`windowScrollable===false`、滚到底后 header/logo 仍可见）＋「内容短于视口时同样不出整页滚动条」（AI 供应商页）。
5. 长导航首尾可达、长内容底部可达 → 用例「长菜单的首尾菜单项均可到达」（工作台/帮助中心均 `toBeInViewport`）＋用例 1 的表格最后一行 `toBeInViewport`。
6. 帮助锚点可达且不被回顶覆盖 → 用例「帮助页锚点定位到目标内容且保持可见」（`/help/foundation#teachers` 直达后锚点小节正文 `toBeInViewport` 且窗口已滚动）。
7. 弹窗独立滚动、不带动导航与主内容 → 用例「弹窗独立滚动 › 弹窗长内容独立滚动，不带动导航与主内容」（480px 视口打开「新建教师」弹窗，滚轮后 footer「保存」进入视口，`mainTop/menuTop/windowY` 不变）。
8. 可观察断言、不同页回顶/同页保位区分 → 见上述 2 与 3 的读数断言；全部断言基于 scrollTop/视口可见性，无组件内部状态。

**测试数字（本机隔离端口实测）：**

- 新增 E2E `global-scroll.spec.ts`：8/8 通过（57.1s）。
- 布局敏感旧 spec 回归批次 19/19 通过：empty-states 5、help-center 5、onboarding 3、platform-switch 2、tour 2、tooltips 1、zz-junior-mode 1（1.1 分钟）。
- 前端套件：`npm run lint` 0 告警（--max-warnings 0）、`npm run build` 通过、`npm run test:unit` 50 文件 404/404 全绿。
- TDD 过程：实现前先跑新 spec 确认红（旧布局下 6 红 2 不可达），实现后转绿；红-绿记录见上。

**实现中遇到并处理的问题：**

- Playwright `mouse.wheel` 的滚动由浏览器异步应用，紧随其后的同步读取会读到旧值（截图里内容已滚、读数为 0）。已加 `waitForScroll` helper（`waitForFunction` 等滚动真正生效再读数）消除竞态。
- e2e 数据库跨用例累积，教师/教室行数不固定，改为等待本批唯一后缀名出现再断言。
- 工具栏 actions 换行后两组 radio 分属两行，滚动量选 15px（内容起始 padding 内）保证两组都可见，点击不触发 Playwright 自动滚动污染基线。
- Windows 下 `mvnw.cmd` 的 java 子进程可能在跑完一轮 e2e 后短暂残留并占用 28080，导致下一轮后端绑定失败；跑测前用 `netstat` 预检端口已空闲。

**保守决策（未扩大范围）：**

- 帮助中心页自身布局不动（规格只要求其锚点「继续可达」，且它是布局外路由，改它反而扩大影响面）。
- 回顶触发条件选 `route.path` 变化：路径变化即视为换页（含 draft id 变化），query/hash 变化一律不回顶；未引入 `router.scrollBehavior`，避免与帮助页自管的窗口滚动互相干扰。
- 未新增 npm 依赖；布局改动纯 CSS + 一段 watch，未拆分/重构页面组件。
