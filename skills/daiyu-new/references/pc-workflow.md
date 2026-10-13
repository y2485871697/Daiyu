# 持久电脑/云端检查协作

源文件：仓库`scripts/cooperation/`；证据和凭据均放仓库外。凭据唯一入口`/workspace/ssh账户密码`，known_hosts必须由既有核验得到，不能禁用校验。手机端电脑检查必须使用已选Linux环境的锁定uv脚本入口：`cooperate.py`内PEP 723声明paramiko 4.0.0，`cooperate.py.lock`固定传递依赖及发行包摘要。uv为该脚本准备独立环境，不复用裸python3或依赖会话激活的旧venv；不向系统Python全局安装包。当前已验证Python 3.14.7。

命令从仓库根目录运行（实际SHA/路径现场核对）：

```sh
uv run --locked --script scripts/cooperation/cooperate.py run --state /workspace/daiyu-cooperation-state --known-hosts <已核验信任文件>
python3 -B scripts/cooperation/cooperate.py cloud --state /workspace/daiyu-cooperation-state
python3 -B scripts/cooperation/cooperate.py offline --state /workspace/daiyu-cooperation-state
```

`run`首次由uv按锁文件准备本机依赖；依赖更新必须显式运行`uv lock --script scripts/cooperation/cooperate.py`并一并审阅、提交锁文件，不在日常运行时解锁。`cloud`、`offline`和标准库测试不导入SSH依赖；纯离线校验直接使用上面的python3命令，不用可能准备依赖/联网的uv入口。缺依赖报告`local_dependency_unavailable`与固定调用提示，和SSH网络/认证失败区分，保留既定云端补缺，不自动删除旧迁移标记或重派旧任务。

状态文件记录dispatch前意图与run绑定；协调器独占锁阻止并发派发。未知请求不重发，先读状态/云端查询。电脑每个scope在独立报告目录持久锁，只派一次；错误修复后如需新attempt必须先确认旧执行停止，不能改编号绕过未知执行。

电脑范围：领域单测/Lint，复用根内Gradle缓存；Gradle任务图拒绝APK/签名/安装/模拟器，仅允许必要资源和JAR编译。云端`mode=checks`有同样任务图门禁、真实退出码、scope与SHA/配置摘要，报告各OS独立，某OS失败不以另一个通过覆盖。系统级隔离不承诺；外写默认空计划，确需普通缓存重定向在审阅入口传入`ExternalWritePlan`，记录并向用户报告。

云端报告离线复用：保留GitHub准确run/attempt/job终态元数据、artifact ID及API摘要、artifact ZIP和单项记录/日志/报告。联网接收时验证GitHub摘要，离线重新校验保存摘要及记录/XML；不同SHA/配置或被篡改报告拒绝。旧已有APK run可由主代理按同样完整证据规范导入，不拿未知旧报告自动套用；本自动入口仅调度可识别checks-only run，不取消APK。

验收包括：正常同步/回收、边界断线缺项补检、同SHA离线成功、不同SHA或配置拒绝、篡改证据拒绝、进程重启不重复派发、云端不打包。任务中间网络丢失把远端状态标未知，不盲重启；已回收报告保留，未可靠回收交云端。

离线云端覆盖必须逐次复验同一run/attempt的Linux与Windows两份原始artifact和bundle，不能仅信任complete标记或保留单平台。来源仅允许github/windows-pc；平台、job、artifact、record身份交叉绑定。协作unittest纳入两平台checks及build门禁。known_hosts目标名称缺失时，仅在观测密钥与已确认信任键完全一致后补齐该目标名称；新密钥或变化不得自动信任。
