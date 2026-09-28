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

管理员恢复 AI
  → MISSIVE_AI_TEAM_ID + force_team=true + remove_shared_labels
  → 同一条会话移回 AI Team，下一条客户消息建立新的 Fin 会话
```

在 Missive 中配置初始队列：**Settings → Accounts → 选择 Live Chat account → Share with… / Sharing options → Team Inbox → 选择 Team**。常用结构是将所有 AI 对话先路由到低提醒的 AI Team，仅在人工接管时由 Bridge 移入值班客服 Team。

## Bridge 的 Missive 配置

真实值仅放在服务器 `/etc/fin-missive-bridge/bridge.env`；模板为 `deploy/bridge.env.example`。所有变量由 `src/main/resources/application.yml` 绑定，禁止写死到 Java、WordPress、Git 或日志。

| 变量 | 作用 | 如何取得 | 生产通常是否替换 |
| --- | --- | --- | --- |
| `MISSIVE_LIVE_CHAT_ACCOUNT_ID` | Live Chat account ID；入站 payload 缺少 `account_id` 时作兜底。WordPress widget 的 `MissiveChatConfig.id` 必须与它一致。 | Settings → Accounts → 目标 Live Chat account → Setup，复制安装代码的 `id`。也可在 Settings → API → Resource IDs 查看。 | 若生产复用同一个 Live Chat account，可不换；否则必须换，且 WordPress 同步换。 |
| `MISSIVE_HANDOFF_TEAM_ID` | 需要人工时将原会话移入的 Team Inbox。 | Settings → API → Resource IDs，或 `GET /v1/teams?organization=<organization-id>`。 | 若生产使用另一人工 Team 则换；复用同一 Team 则可不换。 |
| `MISSIVE_AI_TEAM_ID` | 已恢复 AI 的会话移回的 Team Inbox；通常与 Live Chat account Sharing options 的默认 AI Team 相同。 | Settings → API → Resource IDs，或 `GET /v1/teams?organization=<organization-id>`。 | 若生产使用另一 AI Team 则换。 |
| `MISSIVE_ORGANIZATION_ID` | 创建人工提醒 Post、管理共享标签时指定的 Organization。 | Settings → API → Resource IDs。 | 同一 Workspace 可不换。 |
| `MISSIVE_NEED_HUMAN_LABEL_ID` | “需要人工接管”共享标签 ID。 | 在 Organization 创建/确认共享标签后，从 Settings → API → Resource IDs 获取。 | 同一 Workspace 的同一标签可不换。 |
| `MISSIVE_FIN_AI_PAT` | Bridge 调用 Missive Draft/Post API 的 Bearer Token。 | 使用专门的 `Fin AI`/服务席位登录 Missive：Settings/Preferences → API → Create a new token。 | 同一 Workspace 技术上可复用；生产建议使用独立服务 token，便于最小权限和轮换。 |
| `MISSIVE_WEBHOOK_SECRET` | 验证 Missive → Bridge 入站 webhook 的签名。 | 在 Missive Rule/Webhook 配置中生成并填入同一值。 | 应为生产单独生成。 |
| `BRIDGE_ADMIN_TOKEN` | 保护 Bridge 私有管理员接口，例如恢复 AI。 | 在服务器上生成长随机值，仅写入 `bridge.env`。 | 应使用生产独立值。 |

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
ai team ID = 管理员恢复 AI 后移回哪个 Team
```

Fin 不知道 Missive Workspace，也不直接配置 Missive Team。

## Fin 与其他服务端配置

| 变量 | 作用 | 取得位置 |
| --- | --- | --- |
| `FIN_API_KEY` | Bridge 调用 `/fin/start`、`/fin/reply` 的 Fin Agent API key。 | Intercom/Fin 的 Fin Agent API app 设置。 |
| `FIN_WEBHOOK_SECRET` | 验证 Fin → Bridge webhook。 | Fin Agent API app 的 Webhook Events 设置。 |
| `INTERCOM_CLIENT_SECRET` | 用于 Intercom/Fin webhook 验签的 app client secret。 | Intercom Developer Hub 的 app Authentication/Client Secret。 |
| `FIN_CONVERSATION_ID_PREFIX` | Bridge 创建 Fin 外部 `conversation_id` 的命名空间。生产为 `fin:missive`；测试为 `fin:test:missive`。 | 服务器环境文件，不是 Fin 后台设置。 |
| `FIN_WEBHOOK_RELAY_CONVERSATION_ID_PREFIX` | 唯一 Fin callback 接收端要转发的会话前缀。生产 callback 接收端设为 `fin:test:`。 | 服务器环境文件；测试 Bridge 留空。 |
| `FIN_WEBHOOK_RELAY_TARGET_URL` | 上述测试前缀的本机转发目标，例如 `http://127.0.0.1:8081/webhooks/fin`。 | 服务器环境文件；测试 Bridge 留空。 |
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

### 恢复 AI 处理

当人工客服完成处理、希望后续新消息重新由 AI 回答时，使用受 `BRIDGE_ADMIN_TOKEN` 保护的操作：

```text
POST /admin/conversations/{missive_conversation_id}/resume-ai
X-Bridge-Admin-Token: <BRIDGE_ADMIN_TOKEN>
```

它不会删除客户或客服的历史消息；会在 Missive 中移回 `MISSIVE_AI_TEAM_ID`、移除 `MISSIVE_NEED_HUMAN_LABEL_ID`，并把所有旧 Fin session 标为 `superseded`。因此旧 Fin 的延迟回调会被忽略，客户的**下一条**消息必定通过新的 `/fin/start` 开始全新 Fin 会话。接口不会向客户额外发送消息。此接口仅供运维人员调用，不能置入网页或浏览器代码。

普通 Fin 回复**不再**附带“Talk to a human”链接，也不再创建新的 `handoff_links` token。历史 `handoff_links` 表、服务和 endpoint 暂时保留，使已发出的旧链接可在自身 TTL 内完成接管，也为未来迁移提供参考。

自建聊天前端时，不应将 Missive 或 Fin 的真实 conversation ID 暴露给浏览器。应由 Bridge 在会话初始化时生成随机 `chat_session_token`，由前端保存为受保护的 Cookie 或本地会话凭据，并用它调用未来的 `POST /api/chat/handoff`。Bridge 再由 token 找到内部 `ChatConversation`，复用 `HandoffService` 完成人工接管。

## 生产迁移核对清单

1. 决定生产是否复用测试 Live Chat account、人工 Team、Organization 和共享标签；若复用，测试客户消息会进入相同 Workspace/队列。
2. 若新建生产 Live Chat account：在 Missive 配置它的 Sharing options、在 WordPress 换 widget ID，并在生产 env 换 `MISSIVE_LIVE_CHAT_ACCOUNT_ID`。
3. 在生产服务器写入 root 管理的 `bridge.env`，避免复制测试密钥；至少使用独立 `MISSIVE_WEBHOOK_SECRET` 和 `FIN_WEBHOOK_SECRET`。
4. 重新创建/轮换生产服务 PAT，确认它可访问 Live Chat account、Organization、标签和人工 Team。
5. 在 Missive 与 Fin 分别配置生产 HTTPS webhook URL，并验证签名、回复、人工接管和客服回复。
6. 生产启用前确认域名、Nginx TLS、PostgreSQL、日志目录和 systemd 服务均为生产实例；不要覆盖测试服务器。

### 将当前测试 Bridge 直接升格为生产 Bridge

若不新建生产服务器，而是将当前运行中的测试 Bridge 直接作为生产实例，可以继续使用既有的公网域名、Webhook URL 和签名密钥。此时 **无需** 在 Missive 或 Fin 修改以下回调地址：

```text
https://aiservices.letsgohibachi.com/webhooks/missive/inbound
https://aiservices.letsgohibachi.com/webhooks/fin
```

这是因为外部系统仍然回调到同一个服务实例。该方式的前提是：服务器、域名、Nginx TLS、Bridge 数据库、`MISSIVE_WEBHOOK_SECRET`、`FIN_WEBHOOK_SECRET`、Missive Workspace 与 Fin 配置均保持不变。

升格时仍应完成以下业务切换：

1. 在原 Live Chat account 的 Sharing options 中，将默认 Team Inbox 改为新的“生产 AI 对话”Team。
2. 创建新的“生产人工接管”Team，并将该 Team 的 ID 写入当前服务的 `MISSIVE_HANDOFF_TEAM_ID` 后重启服务。
3. 生产网站使用原 Live Chat account 的 widget ID；若原生产网站已经使用该账号，则无需改 WordPress widget。
4. 确认测试网站不再使用同一 Live Chat account 产生测试消息，避免测试会话混入生产客服队列。
5. 确认现有服务的 PostgreSQL 中保留的是测试历史；它不妨碍新生产会话，但应按生产标准备份、监控和控制访问。

如果之后更换服务器、域名或决定隔离密钥，则必须重新配置 Missive 和 Fin 的两类回调，并使用新的签名密钥。

## 部署位置与环境隔离

当前运行中的服务器实例已作为**生产环境**使用。其部署位置为：

| 内容 | 生产环境 |
| --- | --- |
| JAR | `/opt/fin-missive-bridge/fin-missive-bridge.jar` |
| 环境文件 | `/etc/fin-missive-bridge/bridge.env` |
| systemd 服务 | `fin-missive-bridge` |
| 工作目录 | `/var/lib/fin-missive-bridge` |
| 日志 | `/opt/fin-missive-bridge/log/application.log` |
| PostgreSQL 数据库 | `fin_missive_bridge` |

同一台服务器上的独立测试环境已部署，使用下列**并列且不共享**的位置：

| 内容 | 测试环境 |
| --- | --- |
| JAR | `/opt/fin-missive-bridge-test/fin-missive-bridge-test.jar` |
| 环境文件 | `/etc/fin-missive-bridge-test/bridge.env` |
| systemd 服务 | `fin-missive-bridge-test` |
| 工作目录 | `/var/lib/fin-missive-bridge-test` |
| 日志 | `/opt/fin-missive-bridge-test/log/application.log` |
| PostgreSQL 数据库 | `fin_missive_bridge_test` |
| 本地 HTTP 端口 | 与生产 `8080` 分离，例如 `8081` |

测试与生产还必须使用独立的 Missive Live Chat account、AI Team、handoff Team、Webhook secret；如果两套服务要独立接收 Fin 回调，推荐使用独立的 Fin Agent API app、API key 和 callback URL。

测试实例的公网路由已配置为：

| 用途 | 测试环境 URL |
| --- | --- |
| 健康检查 | `https://aiservices.letsgohibachi.com/test/health` |
| Missive 入站回调 | `https://aiservices.letsgohibachi.com/webhooks/test/missive/inbound` |
| Fin 回调 | `https://aiservices.letsgohibachi.com/webhooks/test/fin` |
| 测试用客户确认页 | `https://aiservices.letsgohibachi.com/test/handoff/...` |

测试服务 `fin-missive-bridge-test` 当前已配置测试侧的 Fin API key，并根据当前决定**复用生产 `MISSIVE_FIN_AI_PAT`**。该 token 可以访问同一 Missive Organization，故测试服务必须仅操作测试账号和测试 Team。它同时**共享生产 Fin App 的 `FIN_WEBHOOK_SECRET`**，使其可以校验该 App 签发的 Fin 回调；该共享并不会让 Fin 事件自动抵达测试服务。

测试与生产当前位于同一个 Missive Organization，因此测试 Bridge 的 `MISSIVE_ORGANIZATION_ID` 使用生产同一值；Live Chat account、AI Team 和 handoff Team 仍使用各自独立的测试 ID。

测试 Bridge 也复用生产的 `MISSIVE_NEED_HUMAN_LABEL_ID`；测试 Missive Rule 的 validation secret 则必须独立生成，并同时保存为测试环境的 `MISSIVE_WEBHOOK_SECRET`。

测试 App 的 `INTERCOM_CLIENT_SECRET` 已配置在测试环境，用于验证 Intercom 页面发出的 `X-Hub-Signature` 测试请求；该值与 Fin 的正式 `X-Fin-Agent-API-Webhook-Signature` 所使用的 `FIN_WEBHOOK_SECRET` 是两个不同的签名密钥。

同一个 Fin App 只能配置一个 callback URL。当前 Bridge 用 `conversation_id` 前缀在这个唯一 callback 后分流，因此无需购买第二个 Fin workspace：

```text
测试 Bridge 创建 fin:test:missive:... 会话
  → 同一个 Fin App
  → Fin 仍回调生产 /webhooks/fin
  → 生产 Bridge 验证 Fin 原始签名
  → 仅 fin:test: 前缀转发至 http://127.0.0.1:8081/webhooks/fin
  → 测试 Bridge 再次验证同一签名并写回测试 Missive 会话

其他前缀（包括既有 fin:missive:...）继续由生产 Bridge 处理。
```

生产 callback 接收端需设置：

```text
FIN_CONVERSATION_ID_PREFIX=fin:missive
FIN_WEBHOOK_RELAY_CONVERSATION_ID_PREFIX=fin:test:
FIN_WEBHOOK_RELAY_TARGET_URL=http://127.0.0.1:8081/webhooks/fin
```

测试 Bridge 需设置：

```text
FIN_CONVERSATION_ID_PREFIX=fin:test:missive
```

其余 relay 变量必须留空。两端必须使用同一 Fin App 的 `FIN_WEBHOOK_SECRET`，以便测试端验证被原样转发的签名。已有的测试 Fin session 仍使用旧的 `fin:missive:` ID；启用前缀后应重置这些 session 或新建测试会话。

源码工作区不部署到服务器。当前本地源码为
`/Users/kelvin.dong/lhbc/code/unified_website/fin-missive-bridge`；Git 仓库为
`git@github.com-lhbc:lovehibachi/missive-ai-bridge.git`。

上述环境文件仅保存服务器本地配置，禁止提交任何真实凭据。
