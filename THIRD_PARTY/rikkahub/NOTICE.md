# RikkaHub 复用说明

来源：https://github.com/rikkahub/rikkahub
固定提交：c94237eaa36c84816ab7e2e6dee9ce82477d4725
许可：GNU Affero General Public License version 3，原文见 LICENSE。
版权归原项目及各贡献者；保留上游的许可、署名与适用通知。

修改者：代鱼项目；修改日期：2026-10-14。

本次改编：
- `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt` 的 OpenAI.baseUrl / responsesPath 分离配置。
- `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt` 的 `"${providerSetting.baseUrl}${providerSetting.responsesPath}"` 地址组合。
- 目标为 `core-domain/.../ResponsesEndpoint.kt` 与 app ModelSettings / 设置界面接线。加上 HTTPS、域名、查询/片段/跨域路径校验，以及常见根域名和 /v1 的便捷适配；不改变已有完整自定义接口地址。

未复制上游请求/回复日志、原始异常/响应正文输出、密钥轮转或自动请求行为。现有独立 Responses 解码、工具确认、终态/存储保护继续保留，不冒称已接入整个 RikkaHub ai 模块或聊天 UI。

新代鱼按用户明确选择采用 AGPLv3；修改及对应完整源码公开于 https://github.com/y2485871697/Daiyu 。APK 源码身份由构建记录中的精确提交提供。第三方各自适用许可仍独立保留。

## 2026-10-26：文本协议兼容与思考映射增量移植

固定来源：`rikkahub/rikkahub@ad2c4430d50ba52392b2941367f98ef59614cb73`，许可 AGPL-3.0；保留以上旧来源记录。

实际改编的上游范围：
- `ai/.../provider/providers/openai/ChatCompletionsAPI.kt` 的 host 专属思考请求族、SiliconFlow 模型集合、Mistral stream_options 例外；`ChatCompletionsStreamDecoder.kt` 的 reasoning_content/reasoning、Mistral thinking 数组与 OpenRouter reasoning_details 累积。
- `ai/.../provider/providers/claude/ClaudeProvider.kt` 的 adaptive thinking/output_config 与默认64,000输出上限；本地旧 MINIMAL 保留明确手动1024预算，不冒充上游 adaptive 档位。
- `ai/.../provider/providers/google/GoogleProvider.kt` 与 `core/Reasoning.kt` 的 thinkingLevel/thinkingBudget 映射；`registry/ModelDsl.kt` 的 tokenizer/顺序匹配用于同一固定 registry 的 Gemini 3/4 分组。档位对应参数，不推断其它模型具有思考能力。
- `ai/.../provider/providers/openai/ResponseAPI.kt` 的 Ark capability 差异，以及 `ResponseApiStreamDecoder.kt` 的可缺省索引与 done 文本兼容。

目标接线：本地 `model/RikkaHubReasoning.kt`、ReasoningRequestPolicy、NativeCompletionRequest/Stream、MessagesRequest/Stream/Model、ResponsesModel/Stream；继续复用现有 ModelPort、Runner、请求冻结与原生历史存储。只展示服务明确返回的思考文本；签名、加密与 redacted 字段仅作原生回传。

未原样引入整个 ai/common 模块、上游 AgentLoop、凭据轮换、原始日志、品牌推广头、EOF成功收束或失败自动重发。没有复制 Square SSE 实现。当前四种文本 Endpoint 的兼容移植不代表额外能力接通：Interactions、Vertex/账户登录鉴权、embedding/图像生成和 Gemini 图片输入不在本增量入口；Messages pause_turn 仍明确中断，不伪装完整结束，也未启用上游自动续发。

修改者：代鱼项目，2026-10-26。对应完整修改源码随 main 公布；Android/供应商实测状态按重构清单与对应验证记录说明。
