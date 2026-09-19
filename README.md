# Fin AI × Missive Live Chat Bridge

Java 8 / Spring Boot 2.7 / PostgreSQL 服务：保留现有 Missive Live Chat widget，让 Fin AI 在同一条 Missive 会话中回复；Fin 或硬规则要求人工时，在该会话创建可见的内部 Post、添加 `需人工` 标签并放回共享 Inbox。

本项目尚未配置、访问或改动任何生产环境。

## 已实现的流程

```text
Missive 入站 Webhook
  -> PostgreSQL 幂等事件表
  -> /fin/start 或 /fin/reply
  -> Fin Webhook（回复片段缓冲）
  -> 完整回复 POST /v1/drafts
  -> 同一条 Missive Live Chat 会话

Fin status=escalated / 硬规则命中
  -> POST /v1/posts（内部提醒 + 橙色需人工标签 + 移入“人工接管”Team Inbox）
  -> 停止这条会话的后续 AI 写回
```

当 Fin 已回复、客户在 60 秒内未继续发言时，服务会在同一会话发送一次低峰周末优惠引导；链接指向 Booking Request 并带 `utm_campaign=fin_low_peak`。每个客户提问轮次最多发送一次，客户再次发言后会为下一轮重新计时。为防止服务重启后骚扰旧会话，默认只处理最近 5 分钟内的 Fin 回复。

每条普通 Fin 回复也会附带一行小字的 “Talk to a human” 链接。链接使用随机一次性 token，GET 只显示确认页；客户确认后，现有人工接管流程会在保留同一会话历史的前提下移入“人工接管”Team Inbox。

服务使用 Fin 的 Webhook 作为可靠业务事件来源，不依赖浏览器 SSE。每个入站事件及 Fin 回复均按外部 ID 去重；不能立即完成的事件会持久化并重试。

## 前置配置

1. 在 Missive 创建服务用户/席位 `Fin AI`，为其生成独立 PAT，并赋予访问网站 Live Chat 会话、创建 Draft 和 Post 的权限。
2. 不要新建第二个 Live Chat 连接帐号。AI 使用 `Fin AI` 的 PAT 鉴权，但 Draft 的 `account` 是已有的 `Website Live Chat` account ID。
3. 在 Missive 建立共享标签 `需人工`，并取得其 ID、组织 ID、Live Chat account ID。另建一个专用的“人工接管”Team Inbox：实际值班客服设为 Active，AI Chat Team 的成员设为 Observer；Team 的回复行为设为“Leave the conversation in the team Inbox”，避免自动分配给个人。
4. 为现有网站 Live Chat 的访客入站消息创建 Missive Rule，动作是 Webhook，URL 为 `https://<bridge-host>/webhooks/missive/inbound`；设置签名密钥。先在隔离/测试通道验证 payload。
5. 在 Intercom/Fin Agent API 设置回调 URL `https://<bridge-host>/webhooks/fin` 与签名密钥。Fin Agent API 需先获 API 访问权限。
6. 在 Fin 中配置 Escalation Guidance / Rules（退款、取消、食品安全、明确要求人工等）。这才是复杂语义的主判断层；服务里的正则仅是确定性第一道防线。

## 环境变量

```bash
export DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/fin_missive_bridge'
export DATABASE_USERNAME='fin_bridge'
export DATABASE_PASSWORD='replace-me'

export MISSIVE_FIN_AI_PAT='...'
export MISSIVE_WEBHOOK_SECRET='...'
export MISSIVE_LIVE_CHAT_ACCOUNT_ID='...'
export MISSIVE_ORGANIZATION_ID='...'
export MISSIVE_NEED_HUMAN_LABEL_ID='...'
export MISSIVE_HANDOFF_TEAM_ID='...'

export FIN_API_KEY='...'
export FIN_WEBHOOK_SECRET='...'
export INTERCOM_CLIENT_SECRET='...'

export LOW_PEAK_FOLLOW_UP_ENABLED='true'
export LOW_PEAK_FOLLOW_UP_DELAY_SECONDS='60'
export LOW_PEAK_FOLLOW_UP_MAX_AGE_SECONDS='300'
export LOW_PEAK_BOOKING_URL='https://lovehibachi.com/booking-request/?utm_campaign=fin_low_peak'

export BRIDGE_PUBLIC_BASE_URL='https://aiservices.letsgohibachi.com'
export HANDOFF_LINK_TTL_MINUTES='1440'
```

可选环境变量：`MISSIVE_API_BASE_URL`、`FIN_API_BASE_URL`、`FIN_API_VERSION`。默认值分别是 `https://public.missiveapp.com`、`https://api.intercom.io`、`2.16`。

密钥不得写入 `application.yml`、Git、浏览器配置或日志。

## 本地启动

数据库及账号配置完成后：

```bash
mvn test
mvn spring-boot:run
```

健康检查：`GET /health` 返回 `{"status":"ok"}`。

Webhook 端点：

| 来源 | 地址 | 验签 Header |
| --- | --- | --- |
| Missive | `POST /webhooks/missive/inbound` | `X-Hook-Signature` |
| Fin | `POST /webhooks/fin` | `X-Fin-Agent-API-Webhook-Signature` |

二者都应部署在 HTTPS 后。服务只在签名正确后返回 `202 Accepted`；后续调用在后台执行，避免 Missive 的 15 秒 Webhook 超时。

## Linux 部署

仓库包含生产化部署的基础文件：

- `deploy/fin-missive-bridge.service`：以低权限 `finbridge` 用户运行的 systemd 服务；
- `deploy/fin-missive-bridge.nginx.conf`：仅暴露 `/health` 和 `/webhooks/` 的 Nginx 反向代理；
- `deploy/bridge.env.example`：服务器环境文件模板，真实文件固定为 `/etc/fin-missive-bridge/bridge.env`，权限应为 `root:finbridge`、`0640`。

应用应绑定 `127.0.0.1:8080`，不直接将 Spring Boot 端口暴露到公网。Nginx 的 HTTP 站点可用于启动验证；在 Missive/Fin 配置真实 Webhook 前，必须给测试服务器绑定域名并配置受信任的 HTTPS 证书。

## 验收顺序

1. 在测试 Live Chat 发一条普通问题，确认 Missive Rule 收到 Webhook，服务调用 `/fin/start`。
2. 确认 Fin 回调的所有 `fin_replied` 被缓冲，只有 `awaiting_user_reply` 后访客才在原窗口看到一条完整回答。
3. 追问一次，确认服务调用 `/fin/reply`，并仍写回同一 Missive 会话。
4. 发送“转人工”或让 Fin 命中 Escalation Rule，确认该会话停止 AI 回复、显示内部通知、加橙色 `需人工` 标签并移入“人工接管”Team Inbox；不应自动指派某位客服。
5. 重放同一 Webhook，确认不会重复调用 Fin、重复回复或重复创建人工提醒。
6. 通过 Missive 实际 Rule payload 核对 `conversation.id`、`latest_message.id`、`latest_message.from_field`、Live Chat account 字段。不同账号/规则的有效负载若不同，应先调整 `MissiveInboundMessage` 解析器再上线。

更多业务约束与完整设计见 [技术设计](../doc/ai_chatbot_design.md) 和 [需求文档](../doc/ai_chatbot.md)。
