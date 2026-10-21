<style>
@media print {
  .toc, nav.toc, .no-print { display: none !important; }
  body { background: #fff !important; color: #000 !important; font-size: 12pt; line-height: 1.6; }
  a { color: #000 !important; text-decoration: underline; }
  h1, h2, h3 { page-break-after: avoid; break-after: avoid; color: #000 !important; }
  h2 { page-break-before: page; break-before: page; }
  h1 + h2, nav + h2 { page-break-before: auto; break-before: auto; }
  pre, table, img, blockquote { page-break-inside: avoid; break-inside: avoid; }
  pre { white-space: pre-wrap; word-break: break-all; border: 1px solid #999; }
  img { max-width: 100% !important; }
  table { border-collapse: collapse; width: 100%; }
  th, td { border: 1px solid #666; padding: 4px 6px; }
}
</style>

# 排课系统安装指南（图文版）

这份指南带你把排课系统装到一台 Windows 或 Mac 电脑上。全程只需要安装一个软件：**Docker Desktop**。不需要安装 Java、Node.js、Maven，也不需要自己装数据库。

> 本文说的“终端”（也叫“命令行”“命令提示符”）是一个可以输入文字命令的黑色窗口：Windows 上叫“命令提示符”或 PowerShell，macOS 上叫“终端”。

> 本文只覆盖“在自己电脑上安装和试用”。HTTPS、域名、公网访问属于生产部署，不在本文范围，详见仓库 [README](../README.md) 的“生产部署清单”。

<nav class="toc">
  <strong>目录</strong>
  <ol>
    <li><a href="#prereq">开始前你需要什么</a></li>
    <li><a href="#step1">第 1 步：安装 Docker Desktop</a></li>
    <li><a href="#step2">第 2 步：下载排课系统</a></li>
    <li><a href="#step3">第 3 步：一键启动</a></li>
    <li><a href="#step4">第 4 步：等待启动完成</a></li>
    <li><a href="#step5">第 5 步：打开浏览器</a></li>
    <li><a href="#step6">第 6 步：首次使用</a></li>
    <li><a href="#daily">日常使用</a></li>
    <li><a href="#faq">常见问题 FAQ</a></li>
    <li><a href="#agent">给 AI Agent 的无人值守安装</a></li>
    <li><a href="#appendix">附录：文件与端口</a></li>
  </ol>
</nav>

<a id="prereq"></a>

## 开始前你需要什么

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Windows 10 / Windows 11（64 位），或 macOS 12 及以上。Linux 也能用，命令与 macOS 相同 |
| Docker Desktop | 免费软件，第 1 步安装。Windows 和 macOS 都用它 |
| 磁盘空间 | 至少 5 GB 空闲，建议留 10 GB（第一次启动要下载和组装运行环境） |
| 内存 | 至少 2 GB 可用内存；建议电脑总内存 8 GB 以上 |
| 网络 | 第一次启动需要联网下载依赖，之后可以离线运行 |
| 时间 | 第一次约 5～15 分钟，以后启动通常 1 分钟内 |

你**不需要**：安装 Java、Node.js、Maven，或单独安装 PostgreSQL 数据库。运行排课系统也不需要管理员权限；只有安装 Docker 时 Windows 可能弹一次管理员授权，按提示允许即可。

Docker 是什么？一句话：它是一个免费软件，把排课系统需要的运行环境打包在“盒子”里，所以你的电脑不用另外装一堆开发工具。

<a id="step1"></a>

## 第 1 步：安装 Docker Desktop

1. 打开 Docker 官方下载页：<https://www.docker.com/products/docker-desktop/>
2. Windows 电脑点 **Download for Windows**；Mac 电脑按芯片选择 **Apple Chip**（M 系列）或 **Intel Chip**。
3. 双击下载好的安装包，一路使用默认选项安装。
4. 安装完成后**重启电脑**。这一步不能省，否则 Docker 可能启动不了。
5. 在开始菜单（Windows）或“应用程序”文件夹（Mac）里打开 **Docker Desktop**。
6. 第一次打开会要求接受服务条款，按提示完成初始设置。
7. 等待 Docker 启动：窗口左下角或任务栏的**鲸鱼图标变绿**，并显示引擎正在运行（Engine running），就说明准备好了。

Windows 如果提示需要启用 WSL 2 或虚拟化，按提示允许并重启电脑。装不上时见 [常见问题](#faq)。

安装完成后，可以在终端里确认版本（Windows 用“命令提示符”，macOS 用“终端”）：

```bat
docker --version
```

macOS：

```bash
docker --version
```

能看到类似 `Docker version 27.x.x` 的输出就说明装好了。

![第 1 步：安装 Docker Desktop](./images/step1-docker-install.png)

<a id="step2"></a>

## 第 2 步：下载排课系统

排课系统的代码放在 GitHub 上，有两种下载方式，任选一种。

### 方式 A：下载压缩包（推荐，不需要安装 git）

1. 在浏览器打开仓库地址：<https://github.com/xue135792468-gif/paiban>
2. 点击绿色的 **Code** 按钮，选择 **Download ZIP**。
3. 下载完成后解压，建议解压到简单路径，例如 `D:\paiban`，并记住这个位置。
4. 打开解压后的文件夹，应该能直接看到 `start.bat`、`start.sh`、`docker-compose.yml` 这些文件。

也可以直接访问压缩包地址（需要先登录有权限的 GitHub 账号）：

```text
https://github.com/xue135792468-gif/paiban/archive/refs/heads/main.zip
```

> 这个仓库是私有的。你需要先让管理员把你的 GitHub 账号加入仓库，并在浏览器里登录，才能下载。如果管理员直接发给你一个 ZIP 压缩包，跳过登录步骤，直接解压即可。

### 方式 B：用 git clone（需要电脑已安装 Git，并且有仓库权限）

macOS / Linux / Git Bash：

```bash
git clone https://github.com/xue135792468-gif/paiban.git
cd paiban
```

Windows 命令提示符：

```bat
git clone https://github.com/xue135792468-gif/paiban.git
cd paiban
```

下载完成后，终端要停留在 `paiban` 这个文件夹里，后面的启动命令都在这里执行。

![第 2 步：下载排课系统](./images/step2-download.png)

<a id="step3"></a>

## 第 3 步：一键启动

启动前先确认：**Docker Desktop 已经打开，鲸鱼图标是绿的**。

### Windows

打开解压后的文件夹，**双击 `start.bat`**。

- 不需要右键选择“以管理员身份运行”，普通双击即可。
- 会弹出一个黑色窗口，自动检查 Docker、检查端口、生成配置，然后构建并启动服务。
- 启动过程中**不要关闭这个窗口**。看到 `[完成] 排课系统已启动` 之后可以关闭，服务会继续在 Docker 里后台运行。

如果你习惯用终端，也可以在文件夹里执行：

```bat
start.bat
```

### macOS / Linux

打开“终端”，先进入排课系统文件夹。小技巧：输入 `cd `（后面有一个空格），然后把文件夹拖进终端窗口，按回车。

```bash
cd /你的路径/paiban
chmod +x start.sh
./start.sh
```

- 加执行权限只需要做一次，作用是让这个文件可以运行。
- 启动过程中**不要关闭终端窗口**。看到 `[完成] 排课系统已启动` 之后可以关闭，服务会继续在 Docker 里后台运行。

![第 3 步：一键启动](./images/step3-start.png)

<a id="step4"></a>

## 第 4 步：等待启动完成

启动脚本会依次打印 5 个步骤：

```text
[1/5] 检查 Docker ...
[2/5] 检查内存与端口 ...
[3/5] 准备 .env 密钥 ...
[4/5] 构建并启动容器（首次可能需要几分钟）...
[5/5] 等待后端就绪（最多 60 秒）...
```

第一次启动需要下载依赖并构建镜像，**通常 5～15 分钟**。期间画面可能停在 `[4/5]` 很久，这是正常的，不要关闭窗口。

看到下面这段就是成功了：

```text
[完成] 排课系统已启动
  前端地址: http://localhost:5173
  后端地址: http://localhost:8080
  默认账号: admin / admin123
```

这是**首次启动**（本次新建了 `.env`）的输出。再次启动时，窗口不会重复打印密码，而是显示：

```text
  默认账号: admin（密码为首次启动时设置的密码；未修改过则为 admin123）
```

判断成功的标志是这一行：`[完成] 排课系统已启动`。

自检命令里的 `✅` 表示该项正常，例如：

```text
✅ Docker：已安装 27.x.x，守护进程运行中
✅ 端口：8080 / 5173 / 5432 均空闲
```

如果看到 `[错误] 后端在 60 秒内没有就绪`，通常是第一次构建还没完全结束。可以加大等待时间再运行一次：

macOS / Linux：

```bash
./start.sh --timeout=180
```

Windows：

```bat
start.bat --timeout=180
```

如果仍然失败，运行自检命令，把输出发给管理员：

macOS / Linux：

```bash
./doctor.sh
```

Windows：

```bat
doctor.bat
```

![第 4 步：等待启动完成](./images/step4-wait.png)

<a id="step5"></a>

## 第 5 步：打开浏览器

1. 打开 Chrome、Edge 或 Safari。
2. 在地址栏输入：<http://localhost:5173>
3. 用默认账号登录：
   - 用户名：`admin`
   - 密码：`admin123`

首次启动时，如果用了 `--admin-password=...`，密码就是指定的那个，启动窗口最后一行会打印出来。之后再次启动，启动脚本不再重复打印密码，只提示“密码为首次启动时设置的密码”。

> 密码只在**第一次创建管理员账号**时生效。系统已经创建过 `admin` 账号后，再改 `.env` 里的 `PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD` 或再传 `--admin-password` 都不会改变已有密码（脚本会给出提示）。忘记密码见 [常见问题](#faq)。

![第 5 步：打开浏览器](./images/step5-browser.png)

<a id="step6"></a>

## 第 6 步：首次使用

第一次登录后，系统会自动进入**初始化向导**；如果没有自动进入，点左侧菜单的“初始化向导”。

按顺序完成下面几步，每一步点“前往设置”填写内容，再回来点“标记完成并继续”：

1. **学校信息**：学校名称、校徽（导出课表时会用到）
2. **学期与作息表**：创建本学期，设置每天几节课、上午/下午/晚自习时间
3. **年级与行政班**：录入年级和班级，可用 Excel 批量导入
4. **教师**：录入教师名单，可用 Excel 批量导入
5. **教室**：普通教室、实验室、操场等场地和容量
6. **课程**：课程目录，以及是否允许连堂、是否必须室外
7. **授课任务**：行政班 + 课程 + 每周节数 + 教师，这是排课的输入
8. **约束规则**：默认已启用教师、行政班、教室不冲突，可按学校情况调整

向导可以随时“跳过”，之后从菜单回来继续，进度会保留。

**登录后请立即修改管理员密码**：点左侧菜单“用户管理”，找到 `admin` 这一行，点“重置密码”，输入新密码并保存。系统目前没有单独的“修改我的密码”页面，管理员给自己重置密码也走这个入口。新密码请记在安全的地方。

![第 6 步：首次使用](./images/step6-onboarding.png)

<a id="daily"></a>

## 日常使用

### 启动

先打开 Docker Desktop，等鲸鱼图标变绿，然后再次双击 `start.bat`（Windows）或在终端执行：

```bash
./start.sh
```

第一次构建完成后，以后启动通常 1 分钟内完成。

### 停止

在排课系统文件夹里执行：

```bash
docker compose down
```

这会停止并删除容器，但**保留所有数据**。下次启动仍然用 `start.bat` 或 `./start.sh`。

查看当前运行状态：

```bash
docker compose ps
```

### 数据放在哪里

| 位置 | 内容 | 注意事项 |
| --- | --- | --- |
| 仓库根目录的 `.env` | 数据库密码、登录密钥、AI 加密密钥、初始管理员密码 | **绝对不要删除**。删除后密钥会重新生成，数据库密码对不上，系统可能连不上数据库，已保存的 AI Key 也会解不开 |
| 仓库根目录的 `data/` | 上传的文件（校徽、Excel 等）和应用数据 | **不要删除**，建议定期备份 |
| Docker 卷 `paiban-pgdata` | PostgreSQL 数据库的全部数据 | 停止时不要加 `-v` 参数，`-v` 会删掉数据库卷 |

备份时，复制 `.env` 和整个 `data/` 文件夹，再导出数据库：

```bash
docker compose exec -T postgres pg_dump -U paiban -d paiban > paiban-backup.sql
```

如果你改过 `POSTGRES_USER` 或 `POSTGRES_DB`，把命令里的 `paiban` 换成你自己的值。

<a id="faq"></a>

## 常见问题 FAQ

### 1. Docker 装不上怎么办？

先看 Docker 官方安装文档：

- Windows：<https://docs.docker.com/desktop/setup/install/windows-install/>
- macOS：<https://docs.docker.com/desktop/setup/install/mac-install/>

Windows 上最常见的三个原因：

1. **没有开启虚拟化**：重启电脑进入 BIOS/UEFI，把 Virtualization（Intel VT-x 或 AMD-V）设为 Enabled。
2. **没有安装 WSL 2**：以管理员身份打开 PowerShell，执行下面的命令，然后重启电脑。
3. **Windows 版本太旧**：Docker Desktop 需要较新的 Windows 10（64 位）或 Windows 11，先运行 Windows Update。

```bat
wsl --install
```

安装完仍然打不开，重启电脑后再试；公司电脑可能被安全软件拦截，需要联系 IT。

### 2. 端口被占用怎么办？

先运行自检，看是哪个端口被占用：

Windows：

```bat
doctor.bat --json
```

macOS / Linux：

```bash
./doctor.sh --json
```

在输出的 `ports` 字段里看三个端口：

```json
"ports": { "8080": "free", "5173": "occupied", "5432": "free" }
```

`occupied` 表示被占用，`free` 表示空闲。

- 如果排课系统**已经在运行**，这三个端口被自己占用是正常的，不用处理。
- 如果是别的程序占用，先找到它并关闭。Windows：

```bat
netstat -ano | findstr :5173
taskkill /PID 12345 /F
```

macOS：

```bash
lsof -nP -iTCP:5173 -sTCP:LISTEN
kill 12345
```

把 `12345` 换成上一条命令查到的进程号（PID）。也可以先停掉本系统再重试：

```bash
docker compose down
```

### 3. 忘记密码怎么办？

**情况 A：还有另一个管理员能登录。** 让他打开“用户管理”，找到忘记密码的账号，点“重置密码”。

**情况 B：只有 `admin` 一个管理员，而且密码忘了。** 注意：改 `.env` 里的 `PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD` **不会**重置已有账号，这个变量只在第一次创建 `admin` 时使用。可行的做法是删掉 `admin` 这一行，让系统在下次启动时按 `.env` 里的密码重新创建。

先停止后端：

```bash
docker compose stop backend
```

用记事本打开仓库根目录的 `.env`，把这一行改成新密码（等号后面直接写新密码，不要加引号）：

```text
PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD=你的新密码
```

然后删除旧的管理员账号并重新启动后端：

```bash
docker compose exec -T postgres psql -U paiban -d paiban -c "DELETE FROM app_user WHERE username = 'admin';"
docker compose up -d backend
```

等后端状态变成 healthy（“日常使用”一节有查看状态的命令），再用 `admin` 和新密码登录。

说明：

- 如果改过 `POSTGRES_USER` / `POSTGRES_DB`，把命令里的两个 `paiban` 换成你的值。
- 删除 `admin` 会同时清空这个账号的初始化向导进度（可以重新走一遍向导），其他业务数据不受影响。
- 不要尝试手工拼一条更新密码的 SQL：密码是加密存储的，直接写明文或随便生成的字符串都登录不了。

### 4. 启动失败怎么办？

第一步，运行自检并看退出码：

Windows：

```bat
doctor.bat --json
```

macOS / Linux：

```bash
./doctor.sh --json
```

退出码含义：

| 退出码 | 含义 | 该怎么办 |
| --- | --- | --- |
| `0` | 健康 | 可以直接使用 |
| `1` | 有警告 | 看 JSON：常见是可用内存低于 2 GB，或数据库尚未迁移 |
| `2` | 严重问题 | 通常是 Docker 没装/没启动、数据库连不上、磁盘不足，先修这些 |

> 端口占用（8080 / 5173 / 5432）只是信息项，**不影响退出码**：系统正常运行时这些端口本来就被排课系统自己占用。健康运行的系统自检结果就是 `0`。

第二步，看后端日志：

```bash
docker compose logs backend
```

只看最后 100 行：

```bash
docker compose logs --tail=100 backend
```

如果是用 `--quiet` 启动的，构建日志在 `.tools/logs/start-compose.log`。

常见原因：

- Docker Desktop 没打开：先打开它，等鲸鱼图标变绿。
- 端口被别的程序占用：见上一节。
- 内存不足：关掉一些程序，或给 Docker Desktop 多分配内存（Settings → Resources）。
- 第一次构建还没结束：加大等待时间，例如 `--timeout=180`。

### 5. Mac 上提示 `Permission denied`

说明 `start.sh` 没有执行权限。在排课系统文件夹里执行：

```bash
chmod +x start.sh
./start.sh
```

如果自检也提示权限不足，一起加上：

```bash
chmod +x start.sh doctor.sh
```

### 6. Windows 双击后窗口一闪就没了

正常情况下窗口会停在最后一步等你按键，不会闪退。如果一闪而过，说明批处理没能启动 PowerShell。请手动打开终端查看错误：

1. 打开排课系统文件夹。
2. 在资源管理器地址栏输入 `cmd`，按回车。
3. 执行：

```bat
start.bat
```

窗口会保留并显示具体错误。如果是公司电脑禁用了 PowerShell，需要联系 IT 放开限制。

<a id="agent"></a>

## 给 AI Agent 的无人值守安装

适用前提：

- Docker Desktop 已安装，并且**正在运行**（打开后鲸鱼图标为绿色）。
- 已经拿到代码，终端当前目录是仓库根目录。
- 不需要响应任何交互式提问。

### 1. 启动

macOS / Linux：

```bash
./start.sh --quiet --admin-password="ChangeMe-2026"
```

Windows：

```bat
start.bat --quiet --admin-password=ChangeMe-2026
```

说明：

- `--quiet`（等价于 `--no-prompt`）不打印进度、不等待按键，失败时直接返回非零退出码。
- `--admin-password=` 只在首次创建 `admin` 账号时生效；已有账号不会被重置。
- 静默模式下，`docker compose` 的构建日志写入 `.tools/logs/start-compose.log`，失败时终端会打印最后 40 行。
- 静默模式仍然会打印最后的 `[完成] 排课系统已启动` 摘要。
- 首次构建可能超过默认的 60 秒健康检查等待；无人值守时建议显式加大：

```bash
./start.sh --quiet --admin-password="ChangeMe-2026" --timeout=300
```

```bat
start.bat --quiet --admin-password=ChangeMe-2026 --timeout=300
```

### 2. 自检

macOS / Linux：

```bash
./doctor.sh --json
```

Windows：

```bat
doctor.bat --json
```

JSON 结构（数值因机器而异）：

```json
{
  "docker": { "installed": true, "version": "27.3.1", "running": true },
  "ports": { "8080": "occupied", "5173": "occupied", "5432": "occupied" },
  "memory": { "total_gb": 15.8, "available_gb": 6.2, "sufficient": true },
  "database": { "reachable": true, "migrated": true, "flyway_version": "17" },
  "disk": { "e_drive_free_gb": null, "project_free_gb": 120.5, "sufficient": true },
  "exit_code": 0
}
```

### 3. 退出码与判定

| 退出码 | 含义 |
| --- | --- |
| `0` | 健康：Docker 正常、数据库可连接且已迁移、内存和磁盘充足 |
| `1` | 有警告：可用内存低于 2 GB，或数据库尚未迁移 |
| `2` | 严重：Docker 未安装/未运行、数据库不可连接，或磁盘不足 1 GB |

端口占用（8080 / 5173 / 5432）只是信息项，不参与退出码计算。

**重要**：`start.sh` / `start.bat` 成功后直接再跑一次 `doctor`，健康运行的系统应返回 `0`。判定安装成功应同时满足：

- `exit_code == 0`（或仅有可解释的内存/迁移警告 `1`）
- `docker.installed == true` 且 `docker.running == true`
- `database.reachable == true` 且 `database.migrated == true`
- 后端健康检查返回 `UP`

### 4. 健康检查

macOS / Linux：

```bash
curl -fsS http://127.0.0.1:8080/api/health
```

Windows：

```bat
curl http://127.0.0.1:8080/api/health
```

期望输出包含：

```json
{"status":"UP","application":"paiban"}
```

### 5. 失败后的固定动作

1. 读取退出码：`1` 是启动失败，`2` 是环境或前置条件不满足。
2. 运行上一节的自检命令，按上面的字段判断。
3. 读取日志：

```bash
docker compose logs --tail=100 backend
```

4. 静默模式下的构建日志：`.tools/logs/start-compose.log`。

<a id="appendix"></a>

## 附录：文件与端口

### 端口

| 端口 | 用途 | 谁能访问 |
| --- | --- | --- |
| `5173` | 前端页面（浏览器访问的地址） | 默认监听所有网卡，同一局域网内的电脑也可能打开 |
| `8080` | 后端 API 和健康检查 | 仅本机 `127.0.0.1` |
| `5432` | PostgreSQL 数据库 | 仅本机 `127.0.0.1` |

后端和数据库只绑定在本机回环地址上；前端 `5173` 默认对所有网卡开放。默认配置适合单机使用，如果这台电脑连在公共网络，建议用系统防火墙限制 `5173` 的访问。要从别的电脑正式访问，属于生产部署范围，需要额外的网络和 HTTPS 配置。

### 文件

| 路径 | 作用 |
| --- | --- |
| `start.bat` | Windows 一键启动入口 |
| `start.sh` | macOS / Linux 一键启动入口 |
| `doctor.bat` / `doctor.sh` | 自检命令 |
| `scripts/start.sh`、`scripts/start.ps1` | 启动脚本的实际逻辑 |
| `scripts/doctor.sh`、`scripts/doctor.ps1` | 自检脚本的实际逻辑 |
| `.env` | 本机配置和密钥。**不要删除**，也不要提交到 git |
| `.env.example` | 配置模板；`start` 脚本会用它生成 `.env` |
| `data/uploads/` | 上传的文件（校徽、导入的 Excel 等） |
| `data/app/` | 应用数据 |
| `docker-compose.yml` | 定义数据库、后端、前端三个容器 |
| `docs/images/` | 本指南用到的截图 |
| `.tools/logs/start-compose.log` | 静默模式下 `docker compose` 的构建日志 |

数据库本身的数据不在 `data/` 里，而在 Docker 卷 `paiban-pgdata` 中。停止服务时不要加 `-v`，否则会删掉数据库卷。

### 相关文档

- 项目说明与本地开发：[README](../README.md)
- 领域术语（行政班、授课任务、基线、调代）：[GLOSSARY](../GLOSSARY.md)
- 架构决策记录：[docs/adr/](./adr/)
