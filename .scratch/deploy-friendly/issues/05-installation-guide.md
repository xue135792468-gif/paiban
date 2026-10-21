# 05: 安装指南（给非技术用户的图文教程）

**What to build:** 撰写给非技术用户的安装指南：分步图文教程、常见问题 FAQ、一键复制命令块、打印友好样式。完成后，教务老师打开 `docs/installation-guide.md`，按照"第 1 步：下载并安装 Docker Desktop"的指引，每步配截图，顺利完成安装。

**Blocked by:** 01, 02, 03, 04

**Status:** ready-for-agent

- [ ] `docs/installation-guide.md` 撰写，包含以下章节：
  - 第 1 步：下载并安装 Docker Desktop（含 Windows/Mac 下载链接与截图占位）
  - 第 2 步：下载排课系统代码（含 GitHub 下载 ZIP 或 git clone 命令）
  - 第 3 步：双击启动（Windows 双击 `start.bat`，Mac/Linux 执行 `./start.sh`）
  - 第 4 步：等待启动完成（含"看到 ✅ 后端启动成功 表示完成"的说明）
  - 第 5 步：打开浏览器访问（含 http://localhost:5173 与默认账号 admin/admin123）
  - 第 6 步：首次使用（含初始化向导的说明）
- [ ] 常见问题 FAQ：
  - Docker 装不上怎么办？（链接到 Docker 官方文档）
  - 端口被占用怎么办？（执行 `doctor.bat` 查看哪个端口被占用）
  - 忘记密码怎么办？（执行 SQL 重置 admin 密码）
  - 启动失败怎么办？（执行 `doctor.bat --json` 获取诊断报告）
- [ ] 一键复制命令块：所有命令用 ```bash 代码块包裹，方便复制
- [ ] 截图占位：每步添加 `![步骤X](./images/stepX.png)` 占位，后续补充实际截图
- [ ] 打印样式：添加 CSS `@media print` 样式，隐藏导航栏与无关元素
- [ ] 人工审阅：请非技术人员（如教务老师）按指南实际操作，记录卡住的地方并优化
