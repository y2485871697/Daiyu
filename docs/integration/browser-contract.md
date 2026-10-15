# 10号浏览器宿主最小契约 v1

本模块仅本地交付；公共入口/依赖/Manifest/CI/注册由1/6号负责。

## 划分

- session包：不可变BrowserOwner(sessionId, kind MAIN|CHILD)、BrowserAccess(DISABLED|READ_ONLY|FULL)、BrowserGrant、BrowserSessionRegistry及0/1/2标签租约。授权在创建时冻结，不因continue升级；会话/标签generation和owner必须精确核对。默认主子各自最多3标签。
- bridge包：BrowserCommand(action:String, tabId:Int?, arguments:Map<String,Any?>)、BrowserHostBridge；消费session接口，将一次action交给BrowserExecutionPort。禁未知动作和参数，在调用执行port前核查权限/owner/lease，不把read_only脚本/DOM操作误判只读。允许的纯读取动作集由session代码集中固定，bridge不另复制权限规则。
- download包：独立BrowserDownloadCoordinator与immutable ticket，create/reserve→完成/失败/取消，session+tab generation owner核，真实非空文件再成功；Android private directory path mapper，不造URI。下载执行器从桥接port调用此组件，未接线不能声称runtime下载完成。
- ported包：保留固定源及现有20主体/三语言资源，Eta专属宿主调用只列接缝、不拷旧AgentBrowserSession/ChildBrowserSession实现。若依赖暂不能接入app Gradle，放独立components/openminis-browser待1号配置，不破坏当前app构建。

## Session接口（本轮由session代理落实，Bridge据此编译）

BrowserGrant(owner:BrowserOwner, access:BrowserAccess)；BrowserOwner(sessionId:String, kind:BrowserOwnerKind)；BrowserTabLease(owner:BrowserOwner, tabId:Int, sessionGeneration:Long, tabGeneration:Long)。
BrowserSessionRegistry.open(grant):Unit；newTab(owner):BrowserTabLease；lease(owner,tabId):BrowserTabLease；assertAllowed(lease,action:String):Unit；closeTab(lease):Unit；close(owner):Unit；takeover(lease):Unit；releaseTakeover(lease):Unit。assertAllowed需拒接管、关闭、generation错与越权；关闭释放回调在锁外。
BrowserExecutionPort.execute(command:BrowserCommand,lease:BrowserTabLease):BrowserExecutionResult；桥接同一次action获取租约→assertAllowed→port→重新核lease有效后允许结果交付，关闭/接管后迟到结果不得交付。真实WebView执行/下载适配存在且有验证证据后才认Android业务完成。

读写策略：READ_ONLY仅get_readable/get_text/find_elements/get_page_info/get_backbone/screenshot；navigate/search/fetch/JS/cookies/scroll等可发网络或改状态，全部FULL。DISABLED连开session/标签均拒绝。主子隔离是资源所有权，不声称站点账号或Cookie隔离。
