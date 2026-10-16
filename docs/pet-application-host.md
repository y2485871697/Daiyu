# P24 应用级桌宠宿主接线

## 产品入口（本次集成）

`ChatApplication.onCreate` 现在唯一创建并强持有 `pets`，连接 controller 的 `CommittedPetTransitions`。
`MainActivity` 的 `ApplicationPetMount` 用真实 FrameLayout 包裹 ComposeView，借用 attach lease；
原生 sibling dispatch 保留透明区域触摸穿透，Activity 重建只释放租约，不 close 应用运行时。
SettingsHome 的 pet 条目分派到真实 PetSettingsScreen，并提供本进程显式 gate 与独立系统授权入口。

`IndependentModelSettings` 使用独立加密 ModelSettingsStore 命名空间及持久绑定记录；桌宠的 assistantId
必须存在且匹配绑定 providerId/localModelId，API model/凭据来自该独立记录，不读取主槽兜底。
配置页保存后写入真实三 ID 到 PetSettings，配置变更撤销旧反应。无需 DB 升版。
完成流来自 ChatRunner 成功 post-commit 的真实 active before / callback after，标题固定为 database.send
接受回执的 title；切换选择、重命名、重组、读历史不会构造完成事件。

新增/扩展测试源码：IndependentModelProductionTest、ControllerAssistantBindingTest、ActivityLifecycleTest；
涵盖真实绑定、精确 ID 拒绝、接受标题、设置入口、Compose 挂载与跨 Activity 同一宿主。
本集成未构建/执行测试；下方保留叶子 API 和授权契约供审核。

生产组合入口是 `app.daiyu.android.pet.PetApplicationHost`。它真实组合现有 `PetHost` → `PetOverlayHost` 以及 `AndroidPetModelPort`，不是另一个桌宠运行时。以下保留宿主 API/接线契约；当前 MainActivity/ChatController 已按上节接入，未改变原图/算法或 manifest。

## 1. 可编译公共 API

```kotlin
PetApplicationHost(
    context: Context,
    resolver: PetModelResolver,
    voice: PetVoicePort? = null,
    onOpenSettings: () -> Unit
)

val host: PetHost
val closed: Boolean
val overlayAllowed: Boolean
val cleanupComplete: Boolean
fun attach(container: FrameLayout, owner: LifecycleOwner): PetCancel
fun setOverlayAllowed(allowed: Boolean)
fun refreshOverlayPermission()
fun updateSettings(settings: PetSettings): PetCommandResult
fun onModelConfigurationChanged(): PetCommandResult
fun connectAssistantTransitions(source: PetAssistantTransitionSource): PetCancel
fun close()

// 同包公开类型
// data class PetAssistantTransition(val before: Message, val after: Message, val title: String)
// fun interface PetAssistantTransitionSource {
//     fun subscribe(listener: (PetAssistantTransition) -> Unit): PetCancel
// }
```

以上 API 列表是声明摘要；下面给出调用代码。所有构造、挂载、设置、close 在主线程执行；source 可在工作线程发事件；两个注册返回的 `PetCancel` 可在任意线程取消。不要把 `PetHost.close()` 当作页面卸载操作。

## 2. 应用持有、Activity 只借用挂载

父 composition root（例如 Application 持有的组件）创建 **一个** `PetApplicationHost`，不能放在每次重组、Activity 的临时字段或每个设置页里重建。传 application context；`onOpenSettings` 使用应用级导航路由/当前前台路由，不捕获旧 Activity。

```kotlin
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import app.daiyu.android.domain.pet.PetCancel
import app.daiyu.android.pet.PetApplicationHost
import app.daiyu.android.pet.PetModelResolver

class ApplicationPetComponent(
    applicationContext: Context,
    resolver: PetModelResolver,
    openPetSettings: () -> Unit
) {
    val pets = PetApplicationHost(applicationContext, resolver, onOpenSettings = openPetSettings)

    fun mount(layer: FrameLayout, owner: LifecycleOwner): PetCancel = pets.attach(layer, owner)
}
```

Activity 用真实 FrameLayout 和自身 LifecycleOwner 调用 `mount`；其销毁只拆卸应用内视图和生命周期观察，不销毁模型运行时/全局根。新 Activity 再挂载同一实例。旧 Activity 的租约迟到取消不会拆新挂载。关闭应用内页面时取消该页面自己的租约，**不要调用应用宿主 close**。`ON_STOP` 仍隐藏应用内视图；全局窗口不因此关闭。

父入口已有的 `PetSettingsScreen(host, modifier)` 必须传 `component.pets.host`；该设置屏仍复用现有真实设置 View。程序化修改用 `pets.updateSettings(settings)`，开关/投喂可用 `pets.host.setEnabled(...)` / `setGlobalVisible(...)` / `feed(...)`。

Android 生产 Application 没有可靠的 `onTerminate` 回调。本类不假造这种系统生命周期；应用组件强持有它，显式业务停用/组件释放或测试清理时在主线程 `close()`。进程结束由 OS 回收，Activity 销毁不是应用组件释放。

## 3. 双重悬浮 gate（不申请、不执行系统权限）

- 每次新应用宿主 `overlayAllowed == false`；磁盘 `globalVisible=true` 不自动开启 gate。
- 父入口收到明确的用户/策略允许后调用 `pets.setOverlayAllowed(true)`；还必须有 `Settings.canDrawOverlays(applicationContext)` 的真实许可。
- false **不是**撤销 Android 系统授权，只是立即禁止本组件窗口；即使 Activity 已拆卸也执行清理。
- 父入口自己负责是否/何时展示系统授权 UI。返回后调用 `pets.refreshOverlayPermission()`；刷新不会偷偷开启显式 gate。也不会发 intent、启动 service 或申请权限。
- `pets.host.snapshot().canDrawOverlays` 在这个组合中是“显式 gate AND 系统许可”的有效值，不能当作独立的 OS 授权真值。父权限 UI 若要区分原因，分别读 `overlayAllowed` 和系统 API。
- gate 拒绝或系统撤销后走原有应用内回退；未首次 attach 前绝不创建全局根。

## 4. 三 ID resolver，不能拿主模型兜底

构造器强制显式注入 `PetModelResolver`，没有默认 ModelSettingsStore、没有主 Agent fallback。父组件负责独立记录的真实查找：

1. 用 `selection.assistantId` 查独立 assistant；缺失返回 null。
2. 校验此 assistant **确实绑定** `selection.providerId` 和 `selection.modelId`。
3. 用 provider/model 两个记录 ID 查真实 provider 配置、模型 API 名和凭据；缺失/失效返回 null。
4. 返回 `ResolvedPetModel(selection, settings)`；其中 `settings.model` 是模型 **API 名**，不是 model 记录 ID。不要通过重贴 selection 把任意主模型配置伪装成命中。

现有 `AndroidPetModelPort` 负责再次逐项比对返回 selection，冻结接收时配置，禁用工具/推理/继承自定义 body，只用现有 Responses/Messages transport、独立 pet session 与 usage。null、异常或任一 ID 不符均不打开替代 transport。

父现有存储是否支持这三类独立记录不是本叶子能凭空保证的；没有真实独立记录时注入返回 null 的 resolver，明确显示独立配置不可用，不能强塞主模型。配置页更改桌宠三 ID 用 `updateSettings`；provider/模型/凭据内容变更但 ID 不变时调用 `onModelConfigurationChanged()`，取消旧反应。下一次真实触发再做新查找，不自动重试旧事件、不发送探测网络请求。

## 5. 真实 assistant 完成事件接线

`ChatController.observe { ... }` 是通用 UI 变更通知，不是可靠完成流。不要用读取历史、对列表做 diff、恢复已完成消息、UI 重组或“最后一个 assistant”来合成事件。

已核实接点：`core-domain/.../ChatRunner.kt` 的 `apply(candidate)` 在 store/atomic checkpoint 成功后才 `publish(next)`；`app/.../ChatController.kt` 创建 ChatRunner 的回调中已有对当前 assistant message/conversation/run 的身份筛选。父接线可在这个回调的 **同一所有者已持久化更新路径** 捕获真实旧 `active` 和收到的 `it`，再转发，不能在写入失败路径转发，也不能把 before 强制 copy 为 RUNNING。title 用该运行所属任务/会话的标题快照，不用可能已切换的当前会话标题。只有真实 RUNNING → COMPLETED、同三种消息身份且 after.error == null 才被领域接收；STOPPED/FAILED/INTERRUPTED 不发反应。

以下是父侧可直接放入 composition 层的轻量 source 示例（不依赖任何未存在的 Controller API）：

```kotlin
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.pet.PetCancel
import app.daiyu.android.pet.PetAssistantTransition
import app.daiyu.android.pet.PetAssistantTransitionSource

class CommittedPetTransitions : PetAssistantTransitionSource {
    private val lock = Any()
    private val listeners = linkedSetOf<(PetAssistantTransition) -> Unit>()

    override fun subscribe(listener: (PetAssistantTransition) -> Unit): PetCancel {
        synchronized(lock) { listeners.add(listener) }
        return PetCancel { synchronized(lock) { listeners.remove(listener) } }
    }

    // 只由父的真实成功 post-commit 路径调用；不回放历史。
    fun afterCommit(before: Message, after: Message, ownerTitle: String) {
        val event = PetAssistantTransition(before, after, ownerTitle)
        val recipients = synchronized(lock) { listeners.toList() }
        recipients.forEach { listener -> runCatching { listener(event) } }
    }
}
```

应用组件创建这个 source 并注册一次：`val lease = pets.connectAssistantTransitions(source)`；父 controller 的上述成功提交路径调用 `source.afterCommit(actualBefore, actualAfter, ownerTitle)`。应用组件更换 controller/source 时重新 connect；旧 source 自动注销。关闭注册只 `lease.cancel()`，不 close 宿主。回调统一排队到主线程并核对注册身份和 active 标记，排队后取消/重绑/close 的事件也不会误投新注册；领域仍持久化 conversation+run 去重。source 的 `subscribe` 不应同步回放任何已有完成消息。

## 6. UNKNOWN 清理与测试

`close()` 永久关闭当前运行时并释放端口/订阅，但如果 WindowManager remove 返回 UNKNOWN，仍强持有原来 **同一个** PetHost/窗口 root；`cleanupComplete == false`。再次 `close()` 重试该 root，不重复关模型/语音，不创建新窗口。父组件不得把这个对象置空然后重建，也不能把 ERROR 当作已无窗口。只有 `cleanupComplete == true` 后才能安全释放组件引用。对象关闭后 attach/connect 失败，设置/权限刷新不会复活它。

本次新增 `PetApplicationHostTest`（androidTest），以 fake WM/source/model、隔离 preferences 检查：

- 双 gate、无首次挂载不创建、Activity 卸载后仍处理系统撤销；
- 同一应用 runtime 跨 Activity、旧租约不拆新挂载；
- UNKNOWN 保留根、可重试、端口只关闭一次、迟到模型结果不复活；
- queued/late source 事件和旧取消的 fence，非法/失败/历史消息拒绝，真实成功去重；
- 三 ID/同 ID 配置变更取消旧反应但不覆盖新反应；
- 生产构造器将真实三 ID 交给 resolver，null 不用 fallback。

已有 AndroidPetModelPortTest 另覆盖 ID 错配/null/异常、接收时冻结/取消、无工具/历史隔离和 usage；已有 PetHostCloseTest 保留原 public host close 回归。本实现未运行构建/测试、联网、系统悬浮授权或真机窗口操作；编译和执行由父代理在集成后验证。
