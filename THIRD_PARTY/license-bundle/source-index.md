# 随包许可与固定来源索引

本索引以集成输入 `af18af3` 的仓内事实收口，由代鱼维护者编写，不是上游 NOTICE，也不是完整 resolved graph / SBOM / 法律兼容性认证。源码发布位置：<https://github.com/y2485871697/Daiyu>；具体 APK 对应的完整提交应由构建记录绑定，不能用 `0.1.0`、此输入基线或本文代替 APK 身份。

`assets/licenses/` 是目标 APK 路径；仓内前缀为 `app/src/main/assets/licenses/`。本目录中的 `bundle.sha256.json` 冻结每一份随包许可/NOTICE/本索引的长度与 SHA-256，同时记录六张鲸鱼 PNG 的**本地字节身份**。仓内原件与随包副本必须逐字相同；检查入口为 `scripts/verify_license_bundle.py`。只有对实际 APK 运行 `--apk PATH` 成功，才有该 APK 中这些路径与摘要的证据；源码目录存在不等于已查 APK。默认检查不下载、不构建、不执行 Gradle、不调用 CI。

## 已有仓内原件的组件—固定版本—源码位置

| 组件 / 使用范围 | 固定来源 / 版本 | 源码、制品与变更证据位置（仓内路径） | 随包许可 / 告知（`assets/licenses/` 下） |
|---|---|---|---|
| 代鱼独立宿主 | 本工程 `0.1.0`；精确源码 revision 由构建记录给出 | 根 `LICENSE`；`app/`、`core-domain/`；保留各复用单元独立许可，不将所有内容一律标为 AGPL | `AGPL-3.0.txt`（根许可原文） |
| RikkaHub Responses 地址配置改编，不是整个 ai/UI 模块 | `rikkahub/rikkahub@c94237eaa36c84816ab7e2e6dee9ce82477d4725` | 上游 `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt`、`ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt`；目标 `core-domain/.../ResponsesEndpoint.kt` 及宿主设置接线；原许可与改动见 `THIRD_PARTY/rikkahub/{LICENSE,NOTICE.md}` | `AGPL-3.0.txt`（与 RikkaHub 原 LICENSE 相同的完整文本）、`rikkahub/NOTICE.md` |
| OpenMinis 浏览器核心 | `OpenMinis/OpenMinis@4ef29002e88db1e20e462ec2ff46916e8a7dcb45`，GPLv3，保留逐文件头精度 | `components/openminis-browser/source-manifest.json` 绑定上游 `src/android/app/src/main/java/com/openminis/app/browser/` 到本地 20 Kotlin / 3 XML。`app/build.gradle.kts` 实际选择编译六个未改上游文件：BrowserAction、BrowserActionInput、BrowserActionResult、BrowserUseJS、InterceptedDialogQueue、BrowserUseManager；另编译 `adapter/src/main/kotlin` 及 app browser 接线。其余快照不因留存自动启用 | 复用已有 `GPL-3.0.txt`（SHA-256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`，与 `THIRD_PARTY/task19/upstream-licenses/openminis-4ef2900-LICENSE` 相同）；`openminis/NOTICE.md` 为本项目来源/变换告知，不冒充上游原 NOTICE |
| 获授权 VirtualDisplay owner 与 Anchor | 用户输入 Eta backend baseline `2a7e7ab92b98da5a3711611017021441e7a8053c`，PolyForm Noncommercial 1.0.0；保留已确认 copyright_owner 授权事实 | 输入位置、归档摘要、32 Java / 19 测试 / Anchor 原 path 及改动详见 `docs/third-party/virtual-display-owner/NOTICE.md`；目标 `app/src/main/java/app/daiyu/android/vd/runtime/`、`app/src/main/kotlin/app/daiyu/android/vd/VirtualDisplayAnchorActivity.kt` | `virtual-display-owner/LICENSE`（含 Required Notice）、`virtual-display-owner/NOTICE.md`；不被根 AGPL 取代，不据本索引重授予许可 |
| 获授权 Linux installer 适配 | 同一 backend baseline `2a7e7ab92b98da5a3711611017021441e7a8053c` | 四个上游 installer/downloader path、授权边界及本地独立提取器见 `THIRD_PARTY/linux-installer/NOTICE.md`；目标 `app/src/main/kotlin/app/daiyu/android/terminal/`。根文件系统不是随包制品，实际下载档案及内部软件另受各自条款约束 | `virtual-display-owner/LICENSE`（共享原许可及 Required Notice）、`linux-installer/NOTICE.md` |
| PRoot、talloc、libandroid-shmem 及构建源归档 | PRoot `5.1.107.92`、talloc `2.4.3`、shmem `0.7`；用户输入 wrapper baseline `2a7e7ab92b98da5a3711611017021441e7a8053c`，不是上游 PRoot commit | `THIRD_PARTY/proot/{NOTICE.md,artifacts.json}` 冻结库/loader、源归档与原输入路径/摘要；源码随 `assets/native-sources/{proot,talloc,shmem,eta-native-build}.tgz` 保留；API34/ABI边界不因 app minSdk26 自动消除 | `NOTICE-proot.txt`、`GPL-3.0.txt`、`proot-5.1.107.92.txt`、`talloc-2.4.3.txt`、`libandroid-shmem-0.7.txt` |
| sherpa-ncnn 及已经核实的 native 依赖 | sherpa `v2.1.15` / `c794e1439fce79932e989220aa1c2848ecbdcdcf`；ncnn `c4193aadbbb56582aa87b1850dd3d98fb8fd936d`；kaldi-native-fbank `v1.21.5`；KISS FFT `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` | `THIRD_PARTY/sherpa-ncnn/NOTICE.md`、`scripts/prepare-speech-runtime.py` 固定发行包 SHA-256 `7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65`、JNI path/ABI及源码；保留既有完整许可，不重新审理其已核来源结论 | `sherpa-ncnn/{LICENSE,LICENSE-ncnn,LICENSE-kaldi-native-fbank,KISSFFT-COPYING,KISSFFT-LICENSE,NOTICE.md,MODEL-CARD.md}` 全部保留原字节 |
| 离线识别模型（非随包权重） | Hugging Face `csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13@05945efc40afe4b572542f01104ca5c413a9f6e1` | 已核固定模型卡 Apache-2.0 证据及七个文件清单见 Sherpa NOTICE、MODEL-CARD 与 `SpeechModelManifest.kt`；只有明确确认后下载。这里只保留模型告知，不表示权重在 APK 中 | `sherpa-ncnn/MODEL-CARD.md`、相应完整许可及 NOTICE；不以引擎许可推定任何其它音色/权重 |
| P32 HTTPS | Maven `com.squareup.okhttp3:okhttp:4.12.0`；上游 tag `parent-4.12.0` | <https://github.com/square/okhttp/tree/parent-4.12.0>；`docs/P32-voice-network.md`；本地 `app/src/main/kotlin/app/daiyu/android/voice/network/` 使用已发布库，不复制整个上游源码 | `P32-voice-network-LICENSES.md`（含 Square 归属与完整 Apache-2.0） |
| P32 WSS | Maven `org.java-websocket:Java-WebSocket:1.6.0`；上游 tag `v1.6.0` | <https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0>；同一 P32 文档与 adapter 路径 | `P32-voice-network-LICENSES.md`（原 MIT、Nathan Rajlich 署名） |
| SLF4J NOP / API | 直接 runtimeOnly `org.slf4j:slf4j-nop:2.0.13`；Java-WebSocket 固定 POM 引用 API `2.0.13`（最终选择仍须解析）；上游 tag `v_2.0.13` | <https://github.com/qos-ch/slf4j/tree/v_2.0.13>；P32 文档和实际 Gradle 声明。不安装日志后端是隐私边界，不是替代库的许可 | `P32-voice-network-LICENSES.md`（原 MIT、QOS.ch 署名） |
| Okio（已知 P32 POM 输入，非本索引声称的实际 resolved 版本） | OkHttp `4.12.0` 上游声明 `3.6.0`；tag `parent-3.6.0` | <https://github.com/square/okio/tree/parent-3.6.0>；P32 文档中的固定 POM 证据；最终版本仲裁需以真实构建解析 | `P32-voice-network-LICENSES.md` 已保存完整 Apache-2.0/归属；不把此版本当成已取证的 APK graph |
| 鲸鱼精灵 / 橱柜 / 米饭图片六张 | 随件声明 `AcidGr/dsh-web-whale-maid`、MIT；**实际导入上游 revision 未闭合** | 现有 `app/src/main/assets/whale-maid/{pet_sprite,cupboard_bg,rice_bowl,rice_grain,rice_pot,rice_spoon}.png` 及原 LICENSE/NOTICE；本次可冻结本地 bytes/hash，但仓内未找到逐图上游原 path/hash/变换链证据。`THIRD_PARTY/task19/ui-assets.md` 的 `7389222e...` 仅参照，不冒认导入 commit | `whale-maid/{LICENSE,NOTICE}` 为现有完整原件副本；原位置 `assets/whale-maid/{LICENSE,NOTICE}` 仍保留。不把本地摘要称为上游绑定完成 |

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

该 Required Notice 在两份适用原件中原样保留。用户已确认的迁入/修改/集成授权不在此重新询问或否定；PolyForm 与 AGPL 组合分发/商业使用的实际授权范围、其它权利材料仍不由本索引提供兼容性法律结论。来源保留不重新判断自研归属，也不要求重写自研算法、UI 或已授权组件。

## 实际直接 runtime 声明：来源已列，resolved / 制品 NOTICE 待补

以下均来自输入基线的真实 `app/build.gradle.kts` `implementation`，**不是计划依赖**。Maven/Google Maven 地址是确定坐标的获取位置，不表示本任务下载或核过该 URL 的制品/源码字节。未在仓内取得这些版本的完整制品 NOTICE/许可证集合时，不写成“许可已进 APK”；通用 Apache 全文、平台 API 许可或另一个库的文本不能替代相应版权/NOTICE。维护者须在精确集成 SHA 获取真实 runtime graph、artifact 与 sources/license 原件，处理传递依赖、版本冲突、variant 与实际打包证据。

| 直接依赖（精确声明） | 可复查真实来源 / 固定版本制品定位 | 当前边界 |
|---|---|---|
| `org.apache.commons:commons-compress:1.28.0` | <https://commons.apache.org/proper/commons-compress/>；<https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.28.0/> | 已从精确版本 Maven JAR 保全 `META-INF/LICENSE.txt`、`NOTICE.txt`，随包路径 `maven/commons-compress-1.28.0/`；JAR与条目摘要见 `THIRD_PARTY/license-bundle/maven/origins.json`。传递项最终选择仍待真实 graph |
| `org.tukaani:xz:1.12` | XZ for Java：<https://tukaani.org/xz/java.html>；<https://repo.maven.apache.org/maven2/org/tukaani/xz/1.12/> | 不与 native XZ Utils 混为一谈；实际版权/许可/public-domain 告知按此 Java 制品源包补，不猜全文 |
| `dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5` | <https://github.com/RikkaApps/Shizuku-API>；Maven Central `dev/rikka/shizuku/{api,provider}/13.1.5/` | 必须取这两个发布制品与适用源码/许可，不用 Shizuku app 根许可或 backend 授权替代；不声称传递项已解析或许可已随包 |
| `top.yukonga.miuix.kmp:miuix-ui-android:0.9.4-rc01`、`miuix-blur-android:0.9.4-rc01`、`miuix-preference-android:0.9.4-rc01` | <https://github.com/compose-miuix-ui/miuix>；Maven Central `top/yukonga/miuix/kmp/<artifact>/0.9.4-rc01/` | 真实 RC 声明照录，不改成稳定版。KMP Android variant、传递 Compose 及各原许可/NOTICE 待实际解析；不凭项目名猜其它 UI/字体/图标来源 |
| `androidx.concurrent:concurrent-futures:1.2.0` | AndroidX <https://android.googlesource.com/platform/frameworks/support/>；<https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.2.0/> | Google Maven 对应 sources/artifact 与适用 license/NOTICE 待取得；不复制 Android 平台实现的声明不覆盖该随包库 |
| `androidx.compose.material3:material3:1.4.0` | 同一 AndroidX 源库；Google Maven `androidx/compose/material3/material3/1.4.0/` | Android variant/Compose runtime 真实选择与 NOTICE 待解析 |
| `androidx.compose.material:material-icons-extended:1.7.8` | 同一 AndroidX 源库；Google Maven `androidx/compose/material/material-icons-extended/1.7.8/` | 库许可/图标归属待取；不能借其存在证明 MoneyBag02 或其它自有图标来源 |
| `androidx.lifecycle:lifecycle-runtime-compose:2.11.0` | 同一 AndroidX 源库；Google Maven `androidx/lifecycle/lifecycle-runtime-compose/2.11.0/` | 实际发布 variant、传递依赖与适用告知待解析 |
| `androidx.navigationevent:navigationevent-compose:1.1.2` | 同一 AndroidX 源库；Google Maven `androidx/navigationevent/navigationevent-compose/1.1.2/` | 同上；不把声明当 resolved graph |
| `androidx.activity:activity-compose:1.13.0` | 同一 AndroidX 源库；Google Maven `androidx/activity/activity-compose/1.13.0/` | 同上 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0`、`kotlinx-coroutines-android:1.11.0` | <https://github.com/Kotlin/kotlinx.coroutines>；Maven Central `org/jetbrains/kotlinx/<artifact>/1.11.0/` | 实际 JVM/Android variant、版本仲裁和对应原许可/NOTICE 待解析；不把同版本 `coroutines-test`（测试用）算作生产直接项 |

根 `build.gradle.kts` Kotlin/Compose 插件 `2.4.21` 与可能隐式标准库同样须按真实解析补齐运行库许可，不能只看 plugin 版本认定最终 stdlib。`debugImplementation androidx.compose.ui:ui-test-manifest:1.9.0` 可能进入 debug APK，debug 包审查须另计；JUnit、Robolectric、coroutines-test、JSON-java 与 androidTest 依赖不是此表中的 release 生产直接项。构建与 CI action 固定原件仍见 `THIRD_PARTY/task19/build-dependencies.md`、`ci-actions.json`；不把构建工具当成 APK runtime。

## 已保全的 Maven 原件（不等于 resolved graph）

`THIRD_PARTY/license-bundle/maven/origins.json` 保留四个精确 Maven 坐标、下载 URL、JAR 长度/SHA-256 与原 ZIP 条目的长度/SHA-256。维护时重新核对 JAR 及八份原文后逐字复制，不重排许可文本。

- 直接生产声明 `org.apache.commons:commons-compress:1.28.0` 的完整 Apache-2.0 与 ASF 专属 NOTICE 随包位于 `maven/commons-compress-1.28.0/{LICENSE.txt,NOTICE.txt}`。
- `commons-codec:commons-codec:1.19.0`、`commons-io:commons-io:2.20.0`、`org.apache.commons:commons-lang3:3.18.0` 为已取得原件的 POM 候选版本，相同两类原件随包位于各 `maven/<artifact>-<version>/`。这不声称它们已进入 APK 或已是最终仲裁版本；真实 runtime graph 仍需核对。
- Maven 制品未内嵌 LICENSE/NOTICE 不自动表示缺少许可；本次不据空条目推断其它组件许可。

## 核验入口与不能证明的事项

- 源码树只读门禁：`python3 scripts/verify_license_bundle.py`。缺文件、空文件、原件/副本不同、SHA-256 或长度变化、未索引许可文件均失败。
- 对父集成已构建的 APK：`python3 scripts/verify_license_bundle.py --apk app/build/outputs/apk/debug/app-debug.apk`（或明确 release APK 路径）。核对每个资产的准确 ZIP entry、重复 entry、大小和 SHA-256；不会解压执行、联网或构建。此结果仅属于所提供的 APK。
- 显式维护操作：`python3 scripts/verify_license_bundle.py --stage`，只从仓内现有完整原件复制，生成/更新本地摘要；应人工审查并提交 diff，**不能放到验证 CI 自动修正输入**。无网络、不下载，不能补出上述缺失制品原件。
- 新增/更新依赖后，父集成另取真实 release/debug runtime 解析结果和制品 NOTICE，不将本入口当成完整依赖许可闭包检查。检查只证明确切字节，不判定合法授权、功能可用、设备运行或自研比例；未运行的测试和 APK 不宣称通过。
