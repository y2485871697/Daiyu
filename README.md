# 代鱼

从空目录建立的 Android 独立工程，当前推进 M0 首个聊天闭环，不是完整助手。

- 包名：`app.daiyu.android`
- 显示名：代鱼；工作目录：`/workspace/代鱼`
- Android 8.0 / API 26 起；目标 API 37；构建 JDK 27（应用字节码目标17）。
- `app`：Android 启动壳；`core-domain`：纯 Kotlin 产品领域层。
- 不依赖旧应用安装、代码、资源、数据格式。
- 首个模型协议为 Responses HTTPS SSE；配置在应用内填写，API密钥由Android Keystore加密。仅允许显式启用的只读电量工具。
- SQLite会话、独立未创建草稿槽、消息块及状态持久化；重启标记中断，不自动重发。当前模型输入窗口为最近50条已保存消息，界面历史每页50条。
- 当前为M0首个增量，不代表24项M0验收全部通过；真实供应商及性能门槛待实测。
- 仅 GitHub Actions 编译 APK；开发包固定使用用户自有原代鱼签名，仍为可调试开发变体，不是正式 Release 包；包名独立，旧应用保持不变。

文档：[架构说明](docs/架构说明.md)、[开发指南](docs/开发指南.md)、[来源清单](docs/来源清单.md)。

## 权利状态

本项目按 GNU Affero General Public License version 3 发布，见 [LICENSE](LICENSE)。对应源码公开于 https://github.com/y2485871697/Daiyu ，APK 的精确源码提交见构建记录。RikkaHub 复用来源、固定版本和修改说明见 [THIRD_PARTY/rikkahub/NOTICE.md](THIRD_PARTY/rikkahub/NOTICE.md)；其他第三方保留各自适用许可及署名。官方 Gradle wrapper 的适用许可与来源单独记录。

## 已确认的范围删减

独立重写不实现小布/小爱厂商助手兼容（自定义模型及 `/agent` 接管）、Gemini/Google系统助手集成（息屏Hey Google检测、锁屏/亮屏唤起自动语音、Google App系统化）、一圈即搜（手势条长按/双指长按）。对应设置、有效配置及后台逻辑均不保留，迁移不启用这些旧配置。普通模型协议（包括Gemini）、手动语音、子代理与诊断仍在保留范围。旧应用与数据不动。
