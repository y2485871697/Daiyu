# T07：非语音原生运行时

基线ae6e834。子任务3295c6c4已只读核验；当前tracked与app构建声明中无已绑定PRoot/talloc/shmem/native库/rootfs，仅wrapper JAR。该范围不是已安装APK扫描，未来候选不继承本结论。

保留PRoot等第三方主体，独立替换历史终端封装；来源不明先保留原件待核，不整体重写。根AGPL或RikkaHub NOTICE不代替原生组件许可。

## 上游参照，不是选定制品版本

- PRoot：https://github.com/proot-me/PRoot/blob/v5.3.0/COPYING 为GPLv2文本；同标签src/cli/cli.c文件头明确v2 or later且含talloc.h。该文件精度GPL-2.0-or-later，不由GitHub标签推成only。v5.3.0仅本次核验参照，未解析tag完整commit、未核所有源树/Android fork/补丁，不等于旧或未来二进制版本。
- talloc：https://github.com/samba-team/samba/blob/talloc-2.4.2/lib/talloc/talloc.h 文件头为LGPLv3 or later且仅指talloc库，不代表全部Samba。参照标签不等于实际制品，静态/动态关系仍未知。
- shmem：尚无足以确定具体项目的证据，不猜共享内存shim或某GitHub项目。

## 9号交接缺项

每项原件及目标路径、size/SHA256、来源fork/完整commit或不可变发行对象、patch顺序及摘要、ELF/ABI/最低API/loader、静态动态链接、构建脚本/工具链/对应源码、原LICENSE/版权/NOTICE，以及APK随包/代下载/用户自装的实际分发关系。

rootfs/工具包另外交发行方snapshot、guest架构、包清单/各软件来源、归档及安装器摘要、安装更新/失败回滚路径。上游许可核验不能代替真实二进制身份，编译一个命令运行器不代表完整PTY/PRoot已安装。

现task-09 handoff自述候选是固定环境命令运行器、非完整交互PTY且未含PRoot安装/mount；这仅交接背景，19号未重新执行其测试。T07仍待实际制品、源码与业务验收。证据为本包tracked-artifacts.json、app/build.gradle.kts及唯一清单R09/T07。

## R09 后续制品记录

上文保留为基线历史，不代表当前打包状态。现已核验并纳入用户提供的两种64位PRoot/loader与对应源码、许可；准确摘要和API34限制见[当前原生制品记录](../proot/NOTICE.md)。此更新未声明实际APK或设备执行通过。
