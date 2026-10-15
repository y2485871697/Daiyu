# 10号浏览器增量交接

本次仅本地main交付；不推送、不CI、不APK、不修改公共入口、Manifest、依赖、工具注册或Skills。
唯一状态账本仍为`docs/重构清单.md`，由1号与合流代码同次更新。

## 已有接口

- `BrowserSessionRegistry.open(grant)`：可信宿主首次绑定授权；同授权重复open幂等，任何授权改变拒绝；关闭后拒绝重开，后续新run必须使用新owner/sessionId。主/子各自0–2三个slot；不声明Cookie或账户隔离。
- `newTab/lease/listTabs`：准确owner/session+tab generation；底层bridge要求显式tabId；BrowserToolSession现提供每owner当前tab默认值及new_tab/close_tab/list_tabs单动作适配。
- `takeover(lease): BrowserTakeoverToken`、`releaseTakeover(token): BrowserTabLease`：进入和退出均换generation；旧结果不能复活。比v1的Unit/旧lease恢复更明确。
- `registerCleanup`绑定精确资源；`onInvalidated`注册下载断流，返回注销句柄。关闭先失效再锁外清理；callback必须有界、不能同步等待WebView/network shutdown。
- `BrowserHostBridge.executeSafely`：入口拒绝=NotExecuted；port进入后的异常/失效=Unknown，不自动重放；成功且lease核验=Confirmed。可信owner来自冻结任务上下文，不来自模型参数。
- `BrowserDownloadCoordinator.reserve/write/cancel/cancelLease/retryCleanup`：真实文件、非空、流式大小限制、SHA-256、全局pending额度；精确lease取消、close/takeover自动撤销；阻塞流须支持prompt abort，网络层必须有超时。清理失败保留记录可重试。
- `BrowserPathMapper`只处理显式private root和mount白名单；privateRoot必须是应用独占、不可被页面/外部进程改名或写入的目录。共享返回Path仅描述映射，不能作为抗并发攻击的文件操作授权；尚无SecureDirectoryStream/Android文件能力适配。

## 动作与参数范围

READ_ONLY仅get_readable/get_text/find_elements/get_page_info/get_backbone/screenshot。
其他已列动作FULL；未知动作/参数拒绝。navigate/fetch仅完整http(s)，不带userinfo。
本轮fetch仅固定GET URL，cookie操作仅约定当前origin；host须实现JSON校验、脱敏与输出限额。
`Long`在Int范围内无损规范化；set_viewport只能完整宽高或reset=true。
门面已接new_tab/close_tab/list_tabs与默认tab；尚不支持search、scroll_and_collect、cookies关键词过滤、内部/minis/file页面。不可因registry有方法就宣称工具协议全覆盖。

## 尚未完成的10号业务工作

1. 保留OpenMinis主体目前只在components暂存，非编译模块；主线程WebView port/UI接管未实现。
2. WebView实际操作与close/takeover必须在同一UI队列准入校验，执行前而非仅入队前；异步交付队列在提交处再fence。
3. 独立HTTP GET下载器已实现受控重定向及连接/读取/总期限，但尚未连接browser_use fetch；Cookie摘要/offload、截屏/正文分页/输出预算、导航回调仍未适配。
4. 真实业务入口由1/6号集成；Android API26和37.2回归未执行，不能复用旧138测试证明本次代码。
5. app JVM测试使用JUnit，1号需补testImplementation；本次不改公共Gradle。当前host编译/测试不是APK构建。
6. closed owner墓碑目前保留至registry销毁；宿主必须按run生命周期持有/销毁registry，不能在同run另建registry绕过冻结授权。

## 清单建议（由1号合流）

P34：进行中。固定源20原件及三语言54键暂存、会话/桥接/真实落盘逻辑与host测试交付；WebView/工具生产注册、下载网络及双API待接。
R10浏览器部分：进行中，Skills部分归11号，不因本次更新改变Skills状态。
T01：来源字节manifest交19号复核，不由10号擅自标已完成。

## 宿主检查基线

`checks/host-5/check.json`与当前五个源码/测试文件SHA-256逐一一致；编译exit 0，JUnit `OK (22 tests)`，真实耗时0.132s（Debian host，无Android运行时）。固定源manifest逐文件散列已复核；app边界脚本exit 0。此记录不是WebView或双API业务验收。

## Phase2本地增量（随对应代码同次提交）

- 合入BrowserToolSession：冻结owner/grant的唯一扁平map入口；new_tab只创建空白资源，支持显式/默认tab及list/close；未知结果不重放。takeover/release仅是门面契约，尚无可用Android恢复后端，不能给真实UI接管完成信用。
- 合入BrowserHttpDownloader：独立GET而非工具注册；跨源后停止转发调用者headers、拒绝HTTPS降级；连接、响应头、流获取及body read共用总deadline；取消只标记并有界投递disconnect/close，底层调用与cleanup实际退出前保持admission；全局CookieHandler须在整个适配器寿命保持null，注入连接工厂须隔离。
- 合入下载reserve/cancel初始化竞态修复：activeReservation保留初始化责任，取消后不返回成功ticket、不遗留.part或提前释放pending。
- 准确最终app候选检查：`/workspace/daiyu-coordination/task-10/checks/phase2-main-1/check.json`，Debian/Kotlin2.4.10 JVM，compile=0、JUnit=0、`OK (74 tests)`，2.731s、source_unchanged=true。CHECK_SHA记录检查时基线3a78fd9，dirty候选准确身份以source_manifest逐文件hash为准；不可把基线SHA当成已提交最终源码。
- HTTP/download独立静态审查`0d8f100e-edd7-40ec-abe9-3d8f0061dbb3`在声明宿主前提内无阻塞；门面原候选审查`9fec3efe-603d-4a8f-ac66-a78abc06df70`无阻塞。
- Android适配器只在交接快照中：`integration-candidate-4`通过类型编译和85项JVM，但独立审查确认takeover/release后无资源恢复、清理投递拒绝丢失销毁责任、异步destroy异常承接及真实导航回调缺口。因此未落本地main、未声明业务完成。固定源23项hash核对无差异。
- P34/R10浏览器维持进行中；唯一账本由1号合流时同次更新；不改公共Gradle/Manifest/工具注册/Skills，不push/CI/APK。API26/API37.2未执行本增量。

## Android manager适配候选（随对应代码同次提交）

- `components/openminis-browser/adapter/`提供真实固定BrowserUseManager调用、租约协程宿主、窄命令映射与拒绝型Google/external/PRoot门面；upstream23项字节hash未改。
- 明确`supportsTakeover=false`，BrowserToolSession在generation改变前拒绝；这修正“READY但无资源”假恢复，并不实现用户UI接管/保留页面恢复。
- 清理拒绝保留责任，可由可信宿主retryCleanup；native destroy只尝试一次，异常由cleanupFailure查询、不会逃出Handler；迟到未挂接资源销毁错误独立保留。cancelled租约留墓碑、不可再create；宿主按run寿命丢弃。
- live包含registry generation；history approval返回后、manager初始化和转发finish前重核。仍未满足“权限变更与native启动共同串行化”的完整生产契约，不声明彻底解决TOCTOU。
- 独立delta review `326833a3-c84c-4f47-af79-9e47836f6ae1`核对了能力拒绝/清理责任/同lease墓碑并指出late unattached销毁错误丢失；主代理按最小修法分离native failure并增确定性测试。
- 最终候选`checks/adapter-integrated-8/check.json`：compile=0、JUnit=0、OK (90 tests)、2.892s、source_unchanged=true。源码manifest精确绑定候选；不是WebView运行、APK或双API证据。其中app宿主74项+adapter协调/映射16项。
- 严格支持子集：http(s) navigate需可信历史许可；get_text(selector)/get_readable/find_elements/get_page_info/get_backbone(max_depth)/普通screenshot。分页max_chars/offset、fetch/cookies/JS/viewport、内部页面/UI仍缺或拒绝，不悄悄忽略。
- 最终业务剩余：真实manager导航start/finish关联和迟到回调、redirect/deny/throw历史、截屏/正文预算、保留页面的UI接管、模块/工具生产接线与API26/API37.2。P34/R10保持进行中，清单仍由1号合流同次更新。

## Phase3参数入口增量（随代码同次提交）

- bridge新增Cookie keywords/fuzzy与scroll_and_collect item_selector/scroll_count/keywords严格参数、不可变列表快照；FULL授权不变。
- `checks/phase3-bridge-1/check.json`精确manifest，compile=0、JUnit=0、OK (93 tests)、source_unchanged=true；仍为JVM，不是Android执行。
- 5路业务实现运行中：lease恢复/导航/输出/fetch/Cookie；本条只登记已落地主代理参数，不把派发标完成。


## Phase3生产组装增量（随代码同次提交，不是业务完成）

- AndroidBrowserRunHost按run持有同一registry/冻结grant；路由WebView、整租约URL、独立Cookie/offload与有界fetch，不得每tool call重建。
- lease observer保持allocation与同资源rebind；并未接用户可见UI接管。清理拒绝保留原allocation责任，不能重放native destroy。
- Cookie读/写真实启动均在startNative内复核sameOrigin，文件IO仍锁外；origin/name顺序回归、迟到发布与清理覆盖。currentUrl只转换已知stale/missing为null，错误线程仍拒绝。
- 正文完整innerText分页、16000字符text/path总预算和真实JPEG验证；正文私有帧不拼/不消费dialog diagnostics。
- 导航后续callback共享完整lease fence，request thread失败转UI，不反向取navigation锁。
- 编译来源显式变化：保留固定BrowserHistory.kt原件/hash，但不参与编译；adapter独立同FQCN兼容类用真实私有文件异步加载/原子持久化。默认history consent仍DENY；关闭前准入事件允许关闭后落盘，导航确认不是持久化成功。
- 精确snapshot9检查：checks/phase3-history-tests-2/check.json compile=0/test=0，OK (164 tests)，3.449s，source_unchanged=true。snapshot8曾因旧测试调用已删delete API编译失败，证据checks/phase3-history-tests-1保留；最终测试按上游clear API修正。
- BrowserProductionCompositionTest提供4项真实设备测试源码：导航/分页/JPEG、CookieManager/offload清理、只读/子owner隔离、whole-lease URL。checks/phase3-instrumentation-typecheck-2=0，API26/API37.2未执行。
- 唯一账本由1号在合流同次更新：P34及R10浏览器维持进行中。公共Gradle/Manifest/工具注册/run入口归1/6号；task10未push/CI/APK、未改共享main。
