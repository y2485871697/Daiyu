# C2 助手迁移接线

## 范围

按最后范围调整，本任务只交付助手；思考按钮弹窗及推理策略由另一个任务负责。本任务未修改 MainActivity、ChatController 或 `[historical-source-snapshot]`。

参考源为只读 `[historical-source-snapshot]`。界面的 Miuix 组件、布局数值、原文、原技能图标和头像裁剪逻辑均从参考抽取；不新增界面说明文字。现有 `AssistantAvatar`、`MiuixScaffoldPage`、`MiuixDialogActions`、`TouchHaptics` 直接复用。原助手选择器使用 `WindowDialog`，没有把它改成 `DaiyuDropdownMenu`。

## 文件映射

所有新 Kotlin 包前缀为 `app.daiyu.android`，以下旧路径均相对于 `[historical-source-snapshot]/kotlin/`：

| 原路径 | 新路径（相对于 app/src/main/kotlin/app/daiyu/android/） |
| --- | --- |
| `data/model/Assistant.kt` | `assistants/Assistant.kt`（去除未引入的 serialization 注解，字段、默认值、原提示词、ID 校验保留） |
| `data/repository/AssistantRepository.kt` 的列表、读取、保存、删除、选中与创建/复制 | `assistants/AssistantStore.kt`（最小接口和 SharedPreferences 实现，不迁移远端服务/技能运行时） |
| `AssistantRepository.kt` 的头像 + `data/repository/AgentMemoryRepository.kt` 中编辑页所需的快照/版本检查/1 MiB 限制 | `assistants/AssistantAssets.kt`（文件实现和可替换的宿主接口） |
| `ui/screens/assistants/AssistantPickerDialogs.kt` | `ui/screens/assistants/AssistantPickerDialogs.kt` |
| `ui/screens/assistants/AssistantEditScreen.kt` | `ui/screens/assistants/AssistantEditScreen.kt` |
| `ui/screens/skills/SkillSwitchRow.kt` 的 `showMenu=false` 分支及 `ui/components/PreferenceIcon.kt` | 编辑页内私有 `AssistantSkillSwitchRow`，保留原 Refresh / DesignServices / Extension 图标和尺寸 |
| `ui/screens/assistants/AvatarCropDialog.kt` | `ui/screens/assistants/AvatarCropDialog.kt`（除包名和 R import 外照搬） |

`[historical-source-snapshot]/res/values{,-b+zh+Hans,-b+zh+Hant}/strings.xml` 中实际使用的助手 key → `app/src/main/res/values{,-b+zh+Hans,-b+zh+Hant}/assistant_ui.xml`；既有 action_save、action_cancel、action_delete、route_skills 复用，未重复定义。

新增 JVM JUnit4 测试：`app/src/test/kotlin/app/daiyu/android/assistants/AssistantStoreTest.kt`。

## 最小数据接口

`AssistantStore`：
- `assistants: StateFlow<List<AssistantProfile>>` / `list()`
- `selectedId: StateFlow<String>`
- `read(id)`、`save(profile)`（upsert）、`delete(id)`、`select(id)`
- 扩展 `create(name, prompt)`、`duplicate(id)`（只复制模型；完整头像和记忆复制由 store-backed picker 处理）

`SharedPreferencesAssistantStore(context)` 使用私有首选项 `daiyu-assistants`、单个 JSON snapshot key `assistant-index-v1`。也可传 `SharedPreferences`，测试用 JVM fake，不需要 Robolectric。写入 commit 成功后才发布状态；至少保留一个助手；删除选中项回退首个；未知选中 ID 拒绝。

`AssistantAssets`：头像读/写/复制/删除，`memory(id)` / `saveMemory(id, content, revision)`、复制/删除记忆。默认 `FileAssistantAssets(context)` 以应用私有目录 `files/daiyu-assistants` 保存头像与 MEMORY.md；内容上限 1 MiB，版本冲突沿用原错误文案。宿主如已有记忆后端，可直接实现该接口，不需引入原 AgentMemoryRepository 全栈。

`AssistantSkill(id, name, description)` 为原技能开关行所需的最小展示值。

Store 和 Assets 均应由宿主创建并保留单一实例，再传给所有助手界面；不是跨进程同步仓库。不自动修改现有聊天/模型/工具运行时状态。

## Composable 接线参数

### 列表受控版

`AssistantPickerDialog(show, assistants, selectedId, onSelect, onEdit, onDismiss, onCopy, onDelete, assets)`

- 前六项为要求的受控展示边界。
- `assistants` 与 `selectedId` 来自宿主状态。
- `onSelect(id)`：宿主决定只改当前会话助手，还是另行 `store.select(id)` 更新全局选中；弹窗随后按原逻辑 dismiss。
- `onEdit(id)`：关闭弹窗后导航人格编辑页。
- `onCopy(id)`、`onDelete(id)`：原长按动作需要的持久化回调，必须接入有效操作；删除确认与最后一个助手不可删的 UI 仍由弹窗保留。
- `assets`：头像读取。

### Store 便捷版

`AssistantPickerDialog(show, store, assets, onSelect, onEdit, onDismiss, selectedId = null)`

自动 collect 列表和全局选中；复制和删除包含头像/记忆文件处理。`selectedId` 可覆盖当前会话助手；`onSelect` 仍由宿主处理，不悄悄替换当前会话或全局状态。

### 人格编辑页

`AssistantEditScreen(assistantId, onBack, store, assets, installedSkills = emptyList(), onSaved = {})`

- `installedSkills` 传宿主实际安装的技能列表，避免迁入原 SkillRuntime。
- `onSaved(id)` 可在保存、头像保存、记忆/技能开关立即保存后触发宿主必要刷新/技能发布；这是替换原 runtime 同步的接线点，不显示任何新文字。
- 姓名/人格提示词/记忆正文通过原保存操作保存；记忆/技能开关与头像保持原立即持久化交互。
- 不存在的助手按原逻辑返回。

## 验证状态

已进行静态源码对照。子代理不能运行 Shell/Gradle；编译与测试由主代理执行。建议运行 `:app:compileDebugKotlin` 和 `:app:testDebugUnitTest --tests app.daiyu.android.assistants.AssistantStoreTest`，并检查三语言资源原文、头像裁切与长按动作。没有声称已做设备视觉验收。
