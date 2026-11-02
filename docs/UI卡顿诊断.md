# UI 卡顿诊断 Release

此包用于留证，不是掉帧修复。编译使用现有 Release 配置（非 debuggable；当前项目 Release 本就未启用 R8），不更改模糊、动画、刷新率和业务数据。仅 `-PdaiyuJankDiagnostics=true` 开启固定帧诊断与 shell profileable；普通 Release 默认关闭。诊断工件、manifest 标记与构建记录单独核验，不能当普通生产包交付。

## 自动采集

安装后，进入应用前台即在现有 Window FrameMetrics worker 采样，不需要打开原“记录诊断日志”开关。原事件日志开关/导出保持原样。覆盖主窗口各页面、现有白名单二级页、侧栏，以及已注册 Compose/Miuix/原生 Dialog；登录与附件接收 Activity 沿用各自 host。未知页面保存为 detail，不写任意标题。

菜单和通用 Popup 分进入/展开/退出/保留期；列表分拖动/其余滚动（不把程序滚动叫惯性）；侧栏分稳定开/关及动画运动（直接拖动不承诺独立phase）；横移/预测返回分拖动、预测、回弹、入场和返回阶段。只观察离散状态，不逐帧读取动画 fraction/触摸坐标，不改变手势仲裁、动画时长及导航动作。阶段并非成功导航或物理呈现确认。

非首绘帧超过 max(两倍预算,16.67ms) 时，保存最多2秒/480帧前段及最多2秒后段；每窗口15秒冷却，单incident最多960帧，后台有界队列写盘，最多保留6份。2秒定时点只保存不完整快照并继续接收迟到长帧；收到覆盖后段结束的帧才标完整，容量截断与pause/detach均标不完整。应用私有 no_backup/jank-diagnostics-v1 下仅保存固定页面/阶段、帧时、阶段耗时、刷新率和时钟锚点；不存聊天、会话/消息ID、URL、路径、截图或输入。

## 系统 trace

普通应用不能自行启动系统级Perfetto。授权Root侧记录器 `scripts/diagnostics/record-ui-jank.sh` 必须由助手的 daemon_start 管理；不使用App工具Shell后台逃逸，不安装/启动/控制界面。未明确授权安装前，只交付APK及脚本，不声称已在设备运行。

记录器直接启动64MiB环形系统trace及4MiB进程元数据，不等待可能已经过去或被系统抑制的READY日志；background-wait后还核对本次唯一名称的STARTED会话与存活PID，服务错误退出不报已布防。每秒检查最多6个诊断JSON的名称、inode、修改秒和大小，原子文件更新或新慢帧日志均触发；日志不是必需条件。触发后延迟2秒停止、落盘并重新布防。会话表匹配已在设备Perfetto v46.0核对；格式不匹配时停止并报错，不猜已启动。刚进入前台至系统trace握手完成之间可能缺少完整前段，应用窗口帧记录仍保留；不把此阶段称为完整trace。最多6份或15分钟加5秒强制收尾上限，保留原始trace、目标PID、gfxinfo与诊断元数据。开始/停止和权限失败写入独立受限目录，不能把零字节/解析失败称为有效采样。会话结束后须明确再次布防，不能声称永久监控。

独立Popup根及视频Surface的呈现依赖系统trace，Activity FrameMetrics不能代表它们。系统文件选择器/权限页/其他App不属于本包页面诊断；系统trace可能包含必要的系统调度元数据，文件只留本机不自动上传。FrameMetrics是窗口回调耗时而非present FPS，无调用栈时不从长帧猜具体函数。对时保留monotonic前/后与boottime配对锚点，按Perfetto clock snapshots映射。

阶段通过固定字符串异步Trace span及计数器关联，API29以上可见，窗口JSON在最低API仍有效。同类多个弹层以span cookie区分，计数器仅辅助，不能代表每个窗口；不记录实例ID。

STOP_TRACING使用trigger_timeout_ms约束等待，不能同时配置duration_ms。64MiB环形缓冲在高负载时可能只保留约20秒调度数据；人工晚触发不能补回已覆盖事件。分析必须核对目标PID、JSON时钟锚点与实际sched/slice保留范围，不能只依据trace_bounds或文件非空。JSON保存可能迟于长帧约2秒，系统trigger另加2秒后段；启动和重新布防间的缺口须单独标记。

六份上限按trace文件计，不是六个唯一事件；同一incident的迟到完整更新可能再次触发。布防与结束PID分别记录，元数据可含旧轮换文件，分析按PID和帧时间范围筛选，不把目录内所有文件直接归入本次trace。触发命令先于元数据复制。
