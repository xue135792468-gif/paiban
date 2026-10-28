# 约束模型评测跑册

固定 C01–C22 语料的真实模型评测。正式结论只认完整 66 次（22 句 × 3 次）；`--smoke` 只用于改提示词后的快速连通检查，**不是**放行证据。

## 前置条件

- 设置中心已有可用供应商和模型，记下 `providerId:modelId`。
- 最多比较 2 个模型；总预算 20 元人民币；`ai_model` 没有价格列，必须给一个保守的名义单价（免费渠道用 `0.001/0.002` 元每 1K token，实际不计费）。
- 先确认渠道能返回：登录后调用 `POST /api/ai-providers/{id}/fetch-models`，再跑一次 `--smoke`。不要凭模型列表判断可用。
- CI 永远不访问真实模型；真实评测只在服务器人工触发。

## 服务器执行

在 `~/paiban` 起一次性后端容器（用生产 compose 的镜像、网络和环境变量）：

```bash
cd ~/paiban
NAME=paiban-eval-$(date -u +%Y%m%dT%H%M%SZ)
sudo -n docker compose -f docker-compose.prod.yml -f docker-compose.override.yml run \
  -d --name "$NAME" --no-deps --entrypoint /bin/sh backend -c '
  mkdir -p /app/data/eval-reports
  chown -R paiban:paiban /app/data/eval-reports
  . /app/secret-bootstrap.sh
  exec gosu paiban java -Xmx320m -XX:MaxMetaspaceSize=160m -jar /app/app.jar \
    --paiban.eval.enabled=true \
    --output=/app/data/eval-reports/constraint-eval-20261028 \
    --model=<providerId>:<modelId> \
    --price-in=0.001 --price-out=0.002 \
    --budget=20 \
    --bind=true
'
```

- `--paiban.eval.enabled` 是唯一带前缀的开关；`--model/--output/--price-in/--price-out/--budget/--smoke/--bind` 都是裸参数。
- `--bind=true` 只在达标时把任务绑定到获胜模型；未达标会自动恢复原配置。
- 快速检查把 `--smoke=true` 加到参数里：只跑 C01 一次，结论显示 `SMOKE`，不参与放行。
- 完整评测通常 10–14 分钟（每次调用约 10 秒，60 秒硬上限）。

## 看进度

容器日志只在结束时打印结论；进度看 `ai_call_log`：

```bash
sudo -n docker exec paiban-postgres-1 psql -U paiban -d paiban -tAc "
  select count(*), max(created_at)
  from ai_call_log
  where task_type='natural_language_constraints'
    and created_at > (now() at time zone 'UTC') - interval '15 minutes'"
```

- `created_at` 是 UTC 的 `timestamp`，`now()` 是 +08 的 `timestamptz`；直接写 `created_at > now() - interval` 会得到 0，误判成“没反应”。
- 任务类型在库里是小写 `natural_language_constraints`，不要用枚举常量的大写去查。
- 如果模型名/渠道不稳，先跑 `--smoke`，不要直接开完整评测。

## 结果与放行

报告落在宿主机 `~/paiban/data/app/eval-reports/constraint-eval-*.md/.json`。

达标条件：**明确支持至少 52/54，边界 12 次零误转**。未达标就如实报告，不打开功能。

通过后：

1. 确认 `ai_task_config` 有 `natural_language_constraints`（custom，provider/model 正确）。
2. 打开功能开关：`PUT /api/settings`，body `{"ai.features.natural-language-constraints":"true"}`（管理员 token）。
3. **刷新后端缓存**：`--bind=true` 只写数据库，运行中的后端不会自动知道。用 `PUT /api/ai-task-config` 重新保存任务分配，或重启后端：
   `sudo -n docker compose -f docker-compose.prod.yml -f docker-compose.override.yml restart backend`
4. 验证：`GET /api/constraint-proposals/availability` 返回 `{"available":true}`；`GET /api/constraint-audits` 返回 200。
5. 清理一次性容器：`sudo -n docker rm -f "$NAME"`。

## 已知坑

- 直连中转做短提示探测时，Cloudflare 会拦 Python-urllib 的 UA（错误码 1010）；命令行探测用 `-A 'claude-cli/2.0.0 (external, cli)'`。能用设置页/应用内的 provider 测试就不要手工解密 key。
- 4096 输出上限对复杂句子会截断；评测与生产任务现用 8192。模型偶尔仍可能超过，报告会标 `TRUNCATED`。
- 60 秒是任务边界；`AiHttpCalls` 覆盖完整 exchange，响应体停滞也会在 60 秒释放。若日志显示 `TIMEOUT`，直接算该次失败，不要用更长的等待来“凑”通过。
