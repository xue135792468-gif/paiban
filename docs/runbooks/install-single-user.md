# 排课系统 · 单用户版异地部署手册

> 给**拿到这个包的人**（或他的 AI 助手）看。目标：一台干净的 Linux 云服务器，从零跑起来，约 15 分钟。
>
> 本版本定位：**单校、单管理员**使用。系统里只有 `admin` 一个管理账号，教师可另建账号登录看自己的课表；多校隔离与细粒度多角色是后续版本的事。

---

## 0. 先确认这几件事

部署前把下面几项问清楚，缺一项都会卡住：

| 项目 | 说明 |
|---|---|
| 服务器 IP、SSH 端口、用户名、密钥/密码 | 需要 sudo 权限 |
| 系统与配置 | 64 位 Linux；建议 **≥2 核 / 4GB 内存 / 40GB 磁盘**（2GB 也能跑，但构建镜像时可能 OOM） |
| 80 / 443 端口是否空闲 | 被别的 nginx/网站占着就会启动失败 |
| 能否访问外网 | 构建要拉 Docker Hub / Maven / npm；不能出网见「离线部署」 |
| 访问域名 | 有域名最好；没有就用 IP + 自签证书（浏览器会提示不安全） |
| 证书 | 有现成的放进 `deploy/certs/`；没有就先用自签，之后再换 |
| 是否要接钉钉 | 要的话准备 AppKey / AppSecret / AgentId / CorpId，并把**服务器出口 IP** 加入钉钉应用白名单 |

---

## 1. 前置条件检查（1 分钟）

```bash
uname -m                  # 期望 x86_64
docker version            # 有 Docker 即可
docker compose version    # 必须有 v2（docker compose，而不是 docker-compose）
df -h /                   # 剩余 ≥ 20GB
```

没有 Docker 时（Ubuntu/Debian）：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

---

## 2. 安装（3 条命令）

把压缩包上传到服务器后：

```bash
tar -xzf paiban-single-user-*.tar.gz
cd paiban-single-user-*/
sudo ./scripts/install.sh --domain 你的域名或IP
```

脚本会自动完成：生成 `.env` 与随机密码 → 生成自签证书（若 `deploy/certs/` 为空）→ 构建并启动 4 个容器 → 等待健康检查 → 打印登录地址与管理员密码。

**幂等**：重复执行不会覆盖已有 `.env`、证书和数据库。第一次运行约 5–10 分钟，主要时间花在构建镜像。

成功后最后一段输出长这样：

```
  访问地址   https://your-host/
  管理员账号 admin
  管理员密码 xxxxxxxx
```

密码同时写在 `.initial-admin-password` 里（权限 600）。**登录后立刻改密码并删掉该文件。**

---

## 3. 验收（必须全过）

在服务器上执行：

```bash
curl -kfsS https://127.0.0.1/api/health          # {"status":"UP",...}
curl -kfsS -o /dev/null -w '%{http_code}\n' https://127.0.0.1/   # 200
sudo docker compose -f docker-compose.prod.yml ps | grep -c healthy   # 4
```

浏览器打开 `https://你的域名/`，用 `admin` 登录，能看到左侧导航和「学期/教师/班级/课程」即可。

---

## 4. 换成真实证书（正式使用必做）

```bash
# 方式一：已有证书
cp fullchain.pem privkey.pem deploy/certs/
chmod 600 deploy/certs/privkey.pem
sudo docker compose -f docker-compose.prod.yml restart nginx

# 方式二：Let's Encrypt（域名须已解析到本机、80 端口可达）
sudo apt install -y certbot
sudo certbot certonly --standalone -d 你的域名
sudo cp /etc/letsencrypt/live/你的域名/fullchain.pem deploy/certs/
sudo cp /etc/letsencrypt/live/你的域名/privkey.pem  deploy/certs/
sudo docker compose -f docker-compose.prod.yml restart nginx
```

> 若前面已经有一层 Cloudflare Tunnel / Caddy / 云负载均衡终止 TLS，本机 nginx 也可以只跑 80；那种情况请把 `docker-compose.prod.yml` 里 nginx 的 `443` 映射去掉，或继续用自签证书、由外层 `noTLSVerify` 回源。

---

## 5. 备份、恢复、升级

```bash
# 备份（数据库 + 上传文件）
COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh

# 恢复（先 dry-run，确认打印的计划再 --yes）
COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh
COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh --yes

# 升级到新版本
git pull            # 或解压新的包覆盖代码目录
sudo ./scripts/install.sh --domain 你的域名      # 会重建镜像并滚动重启，数据不动
```

建议挂每天凌晨的备份 cron：

```cron
0 3 * * * cd /opt/paiban && COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh >> /var/log/paiban-backup.log 2>&1
```

---

## 6. 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `端口 80 已被占用` | 机器上已有 nginx/apache | `ss -ltnp 'sport = :80'` 找到并停掉，或改用别的机器 |
| `exit code 137` / 构建中途被杀 | 内存不足 | 加内存，或先在有 Docker 的机器 build 好再 `docker save`/`load` |
| `no such image` | 拉不到基础镜像 | 检查出网；配 Docker 镜像加速器 |
| 页面 502 | 后端还没起好或崩了 | `docker compose -f docker-compose.prod.yml logs backend --tail=100` |
| 登录后操作报 `403 Invalid CORS request` | 跨域没配 | `.env` 里把公网地址写进 `PAIBAN_CORS_ALLOWED_ORIGINS`，重启 backend |
| 上传/导入报 413 | 文件超过 20MB | 调 `deploy/nginx/conf.d/paiban.conf` 的 `client_max_body_size` |
| 钉钉同步报 `errcode=88` | 出口 IP 不在白名单 | 把服务器公网 IPv4 加入钉钉应用的白名单 |
| PDF 导出中文变方框 | 字体缺失 | 后端镜像已内置 `fonts-noto-cjk`；若自行改过镜像需补装 |

> 本机没有 Docker 时，`docker compose ... config` 之类命令不可用；这是正常现象，不代表配置有错。

---

## 7. 这个版本的边界（先知道，免得误会）

- **单校单管理员**：只有一个管理账号；教师的账号需要管理员在「教师」页面里建，只能看自己的课表、提交调代。
- **数据只有一份**：没有多租户隔离，一台服务器就是一所学校。
- **AI 与钉钉是可选的**：不配也不影响排课、发布、调代；配了才有 AI 导入和通讯录同步。
- **没有自动更新**：升级要手动执行第 5 节的命令。
