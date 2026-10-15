# 20号验收外层：本地 Git 源码状态 gate

## 为什么独立增加一层

`evidence.py` 的完整 SHA 校验只证明调用参数、ledger、record 的 SHA 文本一致；它不读取本地 Git HEAD 或工作区状态。账本自述不能证明当前源码就是接受验收的源码。本工具独立封堵这一缺口，不修改既有证据验证器、其他模块、生产代码、CI 或清单。

**`source_bound` 仅表示：在本次观察时，显式指定的本地仓库 HEAD 等于调用者预先给定的完整 SHA，且 Git 可观察的工作树/索引状态干净。它不表示任何业务验收通过，也不证明实际 compiled APK 来自该源码、构建环境可信、依赖正确、签名正确或真机行为正确。**

不能从 dirty 工作区自动取 HEAD、改写 ledger 的 SHA 或把历史通过记录迁移到新 SHA。应阻塞当前源码验收声明，保留历史记录，另行审查改动和重新取证。

## 调用（仅主代理在本地执行）

Python 3.9+ 标准库、POSIX、可信本地 Git 可执行文件；不需要 Android 构建、网络或额外 Python 包。

```sh
python3 scripts/acceptance/source_gate.py \
  --repo-root /absolute/local/repository \
  --expected-sha "$EXPECTED_FULL_SHA"
```

- 根必须明确给出**绝对路径**，不可用 `..`、symlink 本身或带 symlink 的父路径。不会从子目录隐式发现仓库，不接受 `.git` 文件式 linked worktree/submodule、bare repository 或位于另一仓库内的 nested repository 根。任何 `.git/commondir`（包括目录、symlink 或悬空 symlink）均在调用 Git 前以 `invalid_root` 拒绝，避免实际公共 config 与预检的本地 config 不同；不扩展支持共享元数据布局。
- expected SHA 必须是预先确定的小写完整 40 位 SHA-1 或 64 位 SHA-256；不接受短 SHA、`HEAD`、大写、额外换行或 Git revision 表达式。expected SHA 不进入子进程 argv。
- stdout 是一行 JSON；退出 0 是**仅源码绑定成功**，1 是观察到 dirty / HEAD 不匹配 / 两次 HEAD 改变，2 是非法输入、未知或无法安全验证。**一切非 0 都阻止宣称当前源码已绑定验收。**
- 输出只有固定码、经过格式校验的完整 SHA 和布尔值：`code`、`source_bound`、`clean`，以及适用时的 `expected_sha`、`head_sha`。失败时 `clean: false` 表示未建立干净性，不声称一定已观察到脏文件。不回显路径、文件名、Git stderr、异常、ledger 内容或环境。
- 不提供自由文本 help 输出；未知/缺失参数（包括 `--help`）统一为 `invalid_arguments`。使用本文查看接口。

成功输出示意（SHA 为占位格式，不是真实项目证据）：

```json
{"clean": true, "code": "source_bound", "expected_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "source_bound": true}
```

### 固定码

| code | 意义 |
| --- | --- |
| `source_bound` | 仅源码绑定成立 |
| `head_mismatch` | HEAD 与 expected SHA 不一致 |
| `dirty_source` | Git status 非空，包括 staged、unstaged、untracked |
| `source_changed` | 两次读取 HEAD 不一致 |
| `invalid_arguments` / `invalid_expected_sha` / `invalid_root` | 输入不合约束或不是独立显式仓库根 |
| `nested_repository` | 指定根位于另一仓库内 |
| `unsafe_repository_config` | 仓库 config 无法安全解释或超出保守允许范围 |
| `unsupported_index` | gitlink/submodule、冲突 stage、assume-unchanged、skip-worktree 等可能隐藏状态的索引 |
| `git_error` / `git_timeout` / `git_output_limit` | Git 失败、超时、输出超过上限 |
| `invalid_git_output` / `source_unavailable` | 无法建立可靠观察；fail closed |

## 实际执行边界

唯一的 subprocess 入口使用常量 tuple argv，`shell=False`、stdin/stderr `DEVNULL`，无用户输入拼接。只允许以下固定 Git 子命令：

1. `rev-parse --show-toplevel`，确认 Git 根等于显式根；
2. `rev-parse --verify HEAD^{commit}`，读取完整 commit SHA；
3. `ls-files --stage -z` 与 `ls-files -v -z`，在 status 前拒绝 gitlink、冲突 stage 和隐藏状态的索引标志；
4. `status --porcelain=v1 -z --untracked-files=all --ignore-submodules=none --no-renames`；
5. 再读 HEAD 与 status，发现观察间变化就阻止绑定。

每次 stdout 最多 256 KiB，最多 5 秒；管道按块读取，不用无界 `communicate()` 缓冲；超时或超限 kill/reap。stderr 不收集/不回显。Git 命令总数固定上限 7，未加入文件树递归扫描。所有原始 NUL 分隔文件名仅内部使用，不进入报告。

固定 Git 前缀禁 pager、optional locks、fsmonitor、hooks、untracked cache、外部 diff、自动维护/GC，并禁所有传输协议。子进程环境仅保留执行 Git 所需的 PATH 和固定隔离项，不继承 GIT_DIR/GIT_WORK_TREE、环境配置注入、SSH/proxy、trace 等。禁 system/global config、全局 attributes、终端提示、replace objects 和 lazy fetch；强制检查文件执行位和 ctime，不让本地 core.fileMode/ignoreStat 放宽观察；固定 `core.ignoreCase=false`，防止本地 `core.ignoreCase=true` 把大小写不同的未跟踪文件当成已跟踪路径而漏检。**不执行 ledger argv，不读 ledger，不执行网络、push、配置写入、checkout、clean，也不执行 diff/textconv。** 本工具不写仓库/index/报告文件，只写 stdout。

仅禁 external diff 不足以确保安全：`git status` 也可能通过仓库 filter 配置运行 clean filter。因此先有界读取普通非 symlink `.git/config`（64 KiB），拒绝 include、filter、diff、未知 section、续行/转义和 worktree config；保守接受普通 core/user/remote/branch/init 配置及 SHA-256 objectformat。未知配置直接阻止，不调用 Git。submodule/gitlink 在 status 前拒绝，防止递归加载其他仓库的配置。代价是某些正常但复杂的仓库也会被阻塞；不能为“让它通过”自动更改配置。

### 不能推出的结论

- 这不是对恶意本地 OS、Git 可执行文件、同用户并发篡改的安全沙箱。PATH/Git、仓库元数据和挂载应由主代理控制。根/config 检查与 Git 读取不是一个原子 filesystem snapshot；两次观察可缩小窗口，但无法消除 TOCTOU，也不锁定后续源码。源码应在受控、无并发写者的本地快照中验证，发生修改或重新构建需重新检查。
- Git status 默认不列 ignored 文件，未跟踪且 ignored 的构建输入、外部依赖/环境、被工具忽略的产物不由此证明。不能把 `source_bound` 泛化为“磁盘所有字节匹配提交”，更不能泛化为 APK provenance。稀疏/隐藏索引标志、gitlink 目前明确不支持。
- 既有 `evidence.py` 仍须单独执行并审核原始非空 artifacts；本 gate 不生成业务 pass、不创建 ledger、不验证 artifact 散列、不编译 APK、不触发 CI、不推送。保存 gate 报告请使用受控仓库外位置，避免报告自身成为 untracked dirty；不覆盖历史报告。
- 新增这三个文件在未提交时自然属于 untracked/dirty；不得忽略它们并宣称旧 HEAD 已覆盖新工具。是否提交、何时采集真实 gate 结果由主代理在当前 main 上审查，工具不会建分支或自动提交。

## 独立测试交接

由主代理本地运行：

```sh
python3 -m unittest discover -s tests -p 'test_acceptance_source_gate.py' -v
```

测试只在临时目录建立合成 Git 仓库；`init/add/commit/config/update-index` 仅用于测试夹具，不是 gate 允许的命令。覆盖 clean、工作区/索引/untracked dirty、HEAD mismatch、空仓库、非法 SHA、根/父 symlink、nested repo、commondir 布局在启动 Git 前拒绝、gitlink、隐藏索引、危险 config、Git 失败/timeout/输出限额、观察间变化、固定只读 argv/隔离环境及 stdout 不泄漏。另覆盖 `core.ignoreCase=true` 下大小写不同的未跟踪文件（仅在区分大小写的文件系统运行该用例）。commondir 测试验证拒绝布局，不声称已实证通过该布局执行 filter；`core.checkStat=minimal` 也未作为已证实缺陷。还比较 clean 运行前后 Git 元数据字节以检查无写入。模拟超时与失败，不访问网络、不构建、不跑 CI。这些测试通过也不是应用验收通过。

实现代理只有文件工具，未执行 shell/Git/测试；运行结果须由主代理实际记录。本文和三个非空源码/测试/文档文件不是项目业务通过证据。
