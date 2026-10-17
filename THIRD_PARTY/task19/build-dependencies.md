# T08：当前声明依赖与CI精确固定源

> 下文“当前”指本包原 ae6e834 基线。af18af3 输入已真实声明 P32、压缩、Shizuku、Miuix、AndroidX、coroutines 等直接 runtime，当前固定坐标/来源及尚未取得的制品 NOTICE 边界见 `THIRD_PARTY/license-bundle/source-index.md`。未运行 Gradle 解析，不把此历史清单或新随包字节门禁当作 resolved graph；也不声称这些新增直接项的许可已全部进入 APK。

基线ae6e834。范围是Git跟踪的Gradle声明和workflow直接uses；不是解析后的完整传递树、SBOM或APK内容审计。`declared-build-inputs.json`保留原始四份构建声明，`ci-actions.json`列8个完整40位SHA入口及固定raw许可文本摘要。

## 声明分类

- Gradle9.8.1官方发行/Wrapper：构建工具，发行校验值见gradle-wrapper.properties；现有THIRD_PARTY/gradle-LICENSE保留。Wrapper JAR实际字节由tracked-artifacts.json绑定，不声称本次重新校验发行ZIP到JAR提取链。
- com.android.application 9.5.0-alpha09：构建插件，Google Maven；用户已准预览例外，不改版本。现有来源记录Apache-2.0；本次未取该具体制品POM/源包全文，具体打包依赖另待解析。
- org.jetbrains.kotlin.jvm 2.4.21及生产标准库：编译/运行库声明，Maven Central；既有Apache-2.0记录沿用，具体解析树未跑。
- app生产显式依赖仅project(:core-domain)，不等于无隐式Kotlin依赖或所有未来文件不含第三方。
- junit:junit:4.13.2：测试直接依赖，既有EPL-1.0记录；Hamcrest Core1.3 BSD-3-Clause为已知测试传递项，未重新解析。平台android.test.runner/base是SDK系统测试库，不计复制平台实现。
- API37.2、build-tools37.0.0、命令行工具、Temurin JDK27、托管OS与模拟器镜像属于构建/测试环境，版本配置按workflow，不用公开API接口许可替代SDK/镜像各自实际分发条款；本包未抓全工具安装内容。

## CI许可证固定文本

actions/checkout、setup-java、upload-artifact、download-artifact和android-actions/setup-android为MIT文本；ReactiveCircus/android-emulator-runner为Apache-2.0文本。完整revision、raw URL、原文字节和hash逐项见ci-actions.json，不凭action显示名推版本。只在CI运行，不打入app；action带入的完整依赖树仍未递归审计。

### gradle/actions不能整包记为MIT

固定3f5f9adaf7d9fecd50b5935e54106014257a94e6用于setup-gradle及wrapper-validation。其LICENSE首段明确MIT主体排除专有vendor组件gradle-actions-caching；原件连同NOTICE、DISTRIBUTION.md及licenses/gradle-actions-caching-license.txt保存在upstream-licenses，固定来源/摘要见gradle-actions-distribution-sources.json。

DISTRIBUTION说明增强缓存默认启用，basic或禁用缓存不加载专有缓存组件，并给出`cache-provider: basic`。本基线setup-gradle未显式选basic，不能再笼统说整个action全MIT；本包只核配置与原声明，没有取证每个旧run的实际缓存加载，也不把wrapper-validation等同调用专有组件。此为CI分发组件，不是声称APK混入专有库。

专有许可原件指向Gradle在线条款，其分发说明另指Gradle Technologies条款；在线条款与项目政策是否接受需1号决定，本包不作法律判断。若目标是只用开源缓存，可由1号审阅后为setup-gradle配置basic并运行相关CI；19号不越权改workflow、不自行触发构建，也不捏造批准。没有复制专有实现/二进制，仅留原许可与NOTICE文本作证据。

## 剩余核验

1号在精确集成SHA获取真实解析依赖/制品、工具链分发清单和必要NOTICE；新增依赖与制品不得自动继承此基线结论。完整APK业务与身份验证归总集成/20号。19号不改构建配置、版本、签名、权限或调度CI。
