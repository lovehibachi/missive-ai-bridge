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
  -> POST /v1/posts（内部提醒 + 需人工标签 + Inbox + warning）
  -> 停止这条会话的后续 AI 写回
```

服务使用 Fin 的 Webhook 作为可靠业务事件来源，不依赖浏览器 SSE。每个入站事件及 Fin 回复均按外部 ID 去重；不能立即完成的事件会持久化并重试。

## 前置配置

1. 在 Missive 创建服务用户/席位 `Fin AI`，为其生成独立 PAT，并赋予访问网站 Live Chat 会话、创建 Draft 和 Post 的权限。
2. 不要新建第二个 Live Chat 连接帐号。AI 使用 `Fin AI` 的 PAT 鉴权，但 Draft 的 `account` 是已有的 `Website Live Chat` account ID。
3. 在 Missive 建立共享标签 `需人工`，并取得其 ID、组织 ID、Live Chat account ID。
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

export FIN_API_KEY='...'
export FIN_WEBHOOK_SECRET='...'
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

## 验收顺序

1. 在测试 Live Chat 发一条普通问题，确认 Missive Rule 收到 Webhook，服务调用 `/fin/start`。
2. 确认 Fin 回调的所有 `fin_replied` 被缓冲，只有 `awaiting_user_reply` 后访客才在原窗口看到一条完整回答。
3. 追问一次，确认服务调用 `/fin/reply`，并仍写回同一 Missive 会话。
4. 发送“转人工”或让 Fin 命中 Escalation Rule，确认该会话停止 AI 回复、显示内部通知、加 `需人工` 标签并进入共享 Inbox；不应自动指派某位客服。
5. 重放同一 Webhook，确认不会重复调用 Fin、重复回复或重复创建人工提醒。
6. 通过 Missive 实际 Rule payload 核对 `conversation.id`、`latest_message.id`、`latest_message.from_field`、Live Chat account 字段。不同账号/规则的有效负载若不同，应先调整 `MissiveInboundMessage` 解析器再上线。

更多业务约束与完整设计见 [技术设计](../doc/ai_chatbot_design.md) 和 [需求文档](../doc/ai_chatbot.md)。
