# Fin AI × Missive 配置与运行知识

这份文档记录 Fin AI、Missive Live Chat、Bridge 和将来网站聊天前端之间的边界与配置来源。它不包含任何真实 token、密钥或 ID。

## 当前架构

```text
网站 Missive Live Chat 小组件
  → Missive Live Chat account
  → Missive webhook → Bridge
  → Fin Agent API
  → Fin webhook → Bridge
  → Missive Draft API（写回原客户会话）

Fin Guidance / 硬规则需要人工
  → Bridge 创建 Missive Post、加标签
  → 强制移入人工接管 Team Inbox
```

Bridge 不直接决定新客户会话初始位于哪个 Team。新会话的初始 Team Inbox 由 **Missive Live Chat account 的 Sharing options** 决定；Bridge 只在人工接管时移动会话。

## Missive 对象的区别

| 对象 | 含义 | 在本方案中的作用 |
| --- | --- | --- |
| Organization / Workspace | Missive 的组织空间 | 共享标签和 Team 的所属范围。 |
| Live Chat account | 网站嵌入的客户聊天渠道，例如 `questions@…` | 客户通过它进入聊天；AI 和人工回复均在该会话内。 |
| Team / Team Inbox | 客服工作队列/Team Space | 新消息的默认队列，或人工接管后的队列。 |
| PAT | Missive API Token | 授权 Bridge 调 Missive API；它不是 Live Chat 账号，也不决定默认 Team。 |

同一个 Live Chat account 可以共享给 Team 或用户。一个用户是否能看到某个 Team Inbox，由该用户是否是该 Team 的 Active member 或 Observer 决定。

## 新消息与人工接管的路由

```text
网站 widget 的 MissiveChatConfig.id
  → 指向一个 Live Chat account
  → 该账号在 Missive Sharing options 中配置的 Team Inbox
  → 新客户消息初始出现的 Team

Bridge 检测到人工接管
  → MISSIVE_HANDOFF_TEAM_ID + force_team=true
  → 同一条会话移入人工接管 Team
```

在 Missive 中配置初始队列：**Settings → Accounts → 选择 Live Chat account → Share with… / Sharing options → Team Inbox → 选择 Team**。常用结构是将所有 AI 对话先路由到低提醒的 AI Team，仅在人工接管时由 Bridge 移入值班客服 Team。

## Bridge 的 Missive 配置

真实值仅放在服务器 `/etc/fin-missive-bridge/bridge.env`；模板为 `deploy/bridge.env.example`。所有变量由 `src/main/resources/application.yml` 绑定，禁止写死到 Java、WordPress、Git 或日志。

| 变量 | 作用 | 如何取得 | 生产通常是否替换 |
| --- | --- | --- | --- |
| `MISSIVE_LIVE_CHAT_ACCOUNT_ID` | Live Chat account ID；入站 payload 缺少 `account_id` 时作兜底。WordPress widget 的 `MissiveChatConfig.id` 必须与它一致。 | Settings → Accounts → 目标 Live Chat account → Setup，复制安装代码的 `id`。也可在 Settings → API → Resource IDs 查看。 | 若生产复用同一个 Live Chat account，可不换；否则必须换，且 WordPress 同步换。 |
| `MISSIVE_HANDOFF_TEAM_ID` | 需要人工时将原会话移入的 Team Inbox。 | Settings → API → Resource IDs，或 `GET /v1/teams?organization=<organization-id>`。 | 若生产使用另一人工 Team 则换；复用同一 Team 则可不换。 |
| `MISSIVE_ORGANIZATION_ID` | 创建人工提醒 Post、管理共享标签时指定的 Organization。 | Settings → API → Resource IDs。 | 同一 Workspace 可不换。 |
| `MISSIVE_NEED_HUMAN_LABEL_ID` | “需要人工接管”共享标签 ID。 | 在 Organization 创建/确认共享标签后，从 Settings → API → Resource IDs 获取。 | 同一 Workspace 的同一标签可不换。 |
| `MISSIVE_FIN_AI_PAT` | Bridge 调用 Missive Draft/Post API 的 Bearer Token。 | 使用专门的 `Fin AI`/服务席位登录 Missive：Settings/Preferences → API → Create a new token。 | 同一 Workspace 技术上可复用；生产建议使用独立服务 token，便于最小权限和轮换。 |
| `MISSIVE_WEBHOOK_SECRET` | 验证 Missive → Bridge 入站 webhook 的签名。 | 在 Missive Rule/Webhook 配置中生成并填入同一值。 | 应为生产单独生成。 |

用于检查非敏感资源 ID 的示例（不要将真实 PAT 写进 shell history、文档或聊天记录）：

```bash
curl -H "Authorization: Bearer $MISSIVE_FIN_AI_PAT" \
  "https://public.missiveapp.com/v1/teams?organization=$MISSIVE_ORGANIZATION_ID"
```

Missive 的 Resource IDs 页面通常比 API 查询更适合人工配置迁移。

## PAT、账号和具体会话如何配合

Bridge 收到 Missive webhook 时，从 payload 读取并保存：

- `conversation_id`：目标客户会话；
- `account_id`：该 Live Chat account；
- `to_fields`：向该访客发回消息所需的收件人信息。

Fin 回复后，Bridge 使用 `MISSIVE_FIN_AI_PAT` 认证，并向 `POST /v1/drafts` 提交已保存的 `account`、`conversation` 和 `to_fields`。因此：

```text
PAT = 是否有权限操作 Missive 资源
account_id + conversation_id = 将回复写到哪个客户会话
Sharing options = 新客户会话初始在哪个 Team
handoff team ID = 人工接管后移到哪个 Team
```

Fin 不知道 Missive Workspace，也不直接配置 Missive Team。

## Fin 与其他服务端配置

| 变量 | 作用 | 取得位置 |
| --- | --- | --- |
| `FIN_API_KEY` | Bridge 调用 `/fin/start`、`/fin/reply` 的 Fin Agent API key。 | Intercom/Fin 的 Fin Agent API app 设置。 |
| `FIN_WEBHOOK_SECRET` | 验证 Fin → Bridge webhook。 | Fin Agent API app 的 Webhook Events 设置。 |
| `INTERCOM_CLIENT_SECRET` | 用于 Intercom/Fin webhook 验签的 app client secret。 | Intercom Developer Hub 的 app Authentication/Client Secret。 |
| `BRIDGE_PUBLIC_BASE_URL` | Bridge 对外 HTTPS 基址。当前主要用于保留的旧 handoff URL；未来自建前端也会使用 API 基址。 | 部署域名，例如 `https://aiservices.letsgohibachi.com`。 |

Fin Webhook URL 为 `/webhooks/fin`；Missive 入站 Webhook URL 为 `/webhooks/missive/inbound`。两者必须使用 HTTPS，并分别使用独立签名密钥。

## WordPress 边界

当前 WordPress 只负责加载 Missive widget。其 `MissiveChatConfig.id` 位于：

`shop.lovehibachi.com_20260626_181730/wp-content/uploads/custom-css-js/157.js`

WordPress 不保存 Fin key、Missive PAT、webhook secret、人工 Team ID，也不执行转人工逻辑。生产部署若使用不同 Live Chat account，需同时修改 WordPress widget ID 和生产 Bridge 的 `MISSIVE_LIVE_CHAT_ACCOUNT_ID`。

## 目前的人工接管与未来自建前端

当前可触发人工接管的方式：

- Fin Guidance 返回不对客户展示的 `[[LH_HUMAN_HANDOFF]]`；
- Bridge 硬规则命中；
- Fin `fin_status_updated: escalated`。

Bridge 随后保持同一 Missive 会话历史，创建内部提醒、加共享标签，并移入人工接管 Team。人工处理后，Bridge 不再向该会话发送 AI 回复。

普通 Fin 回复**不再**附带“Talk to a human”链接，也不再创建新的 `handoff_links` token。历史 `handoff_links` 表、服务和 endpoint 暂时保留，使已发出的旧链接可在自身 TTL 内完成接管，也为未来迁移提供参考。

自建聊天前端时，不应将 Missive 或 Fin 的真实 conversation ID 暴露给浏览器。应由 Bridge 在会话初始化时生成随机 `chat_session_token`，由前端保存为受保护的 Cookie 或本地会话凭据，并用它调用未来的 `POST /api/chat/handoff`。Bridge 再由 token 找到内部 `ChatConversation`，复用 `HandoffService` 完成人工接管。

## 生产迁移核对清单

1. 决定生产是否复用测试 Live Chat account、人工 Team、Organization 和共享标签；若复用，测试客户消息会进入相同 Workspace/队列。
2. 若新建生产 Live Chat account：在 Missive 配置它的 Sharing options、在 WordPress 换 widget ID，并在生产 env 换 `MISSIVE_LIVE_CHAT_ACCOUNT_ID`。
3. 在生产服务器写入 root 管理的 `bridge.env`，避免复制测试密钥；至少使用独立 `MISSIVE_WEBHOOK_SECRET` 和 `FIN_WEBHOOK_SECRET`。
4. 重新创建/轮换生产服务 PAT，确认它可访问 Live Chat account、Organization、标签和人工 Team。
5. 在 Missive 与 Fin 分别配置生产 HTTPS webhook URL，并验证签名、回复、人工接管和客服回复。
6. 生产启用前确认域名、Nginx TLS、PostgreSQL、日志目录和 systemd 服务均为生产实例；不要覆盖测试服务器。

## 运维位置

测试服务器当前使用：

- JAR：`/opt/fin-missive-bridge/fin-missive-bridge.jar`
- 环境文件：`/etc/fin-missive-bridge/bridge.env`
- systemd：`fin-missive-bridge`
- 日志：`/opt/fin-missive-bridge/log/application.log`
- PostgreSQL：`fin_missive_bridge`

这些路径是部署约定，不是可提交凭据的位置。
