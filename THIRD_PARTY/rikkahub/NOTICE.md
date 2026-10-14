# RikkaHub 复用说明

来源：https://github.com/rikkahub/rikkahub
固定提交：c94237eaa36c84816ab7e2e6dee9ce82477d4725
许可：GNU Affero General Public License version 3，原文见 LICENSE。
版权归原项目及各贡献者；保留上游的许可、署名与适用通知。

本次改编：
- `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt` 的 OpenAI.baseUrl / responsesPath 分离配置。
- `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt` 的 `"${providerSetting.baseUrl}${providerSetting.responsesPath}"` 地址组合。
- 目标为 `core-domain/.../ResponsesEndpoint.kt` 与 app ModelSettings / 设置界面接线。加上 HTTPS、域名、查询/片段/跨域路径校验，以及常见根域名和 /v1 的便捷适配；不改变已有完整自定义接口地址。

未复制上游请求/回复日志、原始异常/响应正文输出、密钥轮转或自动请求行为。现有独立 Responses 解码、工具确认、终态/存储保护继续保留，不冒称已接入整个 RikkaHub ai 模块或聊天 UI。

新代鱼按用户明确选择采用 AGPLv3；修改及对应完整源码公开于 https://github.com/y2485871697/Daiyu 。APK 源码身份由构建记录中的精确提交提供。第三方各自适用许可仍独立保留。
