# T03 / T07：语音来源与制品绑定

当前归属：用户明确确认本节语音业务实现为其 fork 后原创，保留代码与既有行为；历史输入只定位版本，不据以推定上游作者或要求重写。实际第三方网络库、引擎、JNI 主体及模型各按其真实来源与许可保留。以下阶段记录中的原创待核措辞不再适用于本项。

基线ae6e834；只读核验824aed2f已完成。现有RikkaHub NOTICE严格限两段Provider地址语义，不改成整个网络/聊天/TTS模块。固定上游c94237eaa36c84816ab7e2e6dee9ce82477d4725原LICENSE与已存副本字节相同，34523B，SHA256见upstream-licenses/sources.json。

## 已登记改编

源https://github.com/rikkahub/rikkahub/tree/c94237eaa36c84816ab7e2e6dee9ce82477d4725
- ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt：OpenAI baseUrl/responsesPath分离。
- ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt：地址组合。

固定文件存在；改编语义依据本地既存NOTICE，本轮不冒充完整逐行比较。目标ResponsesEndpoint、ModelSettings和设置接缝的HTTPS/域名/路径校验、裸域名与/v1便捷适配为宿主增量。不导入上游日志、原始异常或密钥轮转。

## TTS候选，不是当前使用清单

固定speech/src/main/java/me/rerere/tts/provider/providers/存在ElevenLabs、FishAudio、Gemini、Groq、MiMo、MiniMax、OpenAI、Qwen、Step、System、Volcengine、XAI的TTSProvider文件。目录与根AGPLv3是定位证据，不证明特定builder/decoder/音色已迁入；具体文件头、NOTICE与依赖仍要核。

14号需按实际保留单元交：builder参数/函数、decoder/分帧格式、音色表/试听素材、冻结旧发布SHA/path/hash、本地移植补丁、上游固定source path/commit/license、目标path/候选SHA。自研语音控制/UI/取消owner与第三方适配、历史适配封装分别列，不重做已确认自研。

## 运行库、JNI与模型分开

- Sherpa仅名称不确定实际采用sherpa-onnx或其它实现；候选官方k2-fsa/sherpa-onnx的master LICENSE参照显示Apache-2.0，但移动分支许可不是实际版本绑定。
- Tencent/ncnn的master LICENSE.txt参照说明主体BSD-3-Clause并列其它条款；不能用单个BSD标签覆盖完整打包依赖。
- JNI/AAR/SO逐项需ABI、size/SHA256、source commit、构建工具/脚本/patch、对应源码及原许可/NOTICE。
- STT/TTS/VAD模型与音色权重各需准确名称、官方发行对象、版本/摘要、独立许可/署名；运行库许可不覆盖权重或数据。
- 随包、代下载、用户导入三种分发与生产加载入口分开登记。

官方候选定位：https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE ；https://github.com/Tencent/ncnn/blob/master/LICENSE.txt 。以上不是选定制品版本。tracked baseline当前无这些native/model制品；这不代替APK扫描或未来迁入核验。不因缺项删自研/合法第三方，也不把保留功能决定当整模块原创证明。T03已有地址范围不扩大，TTS/T07真实文件绑定与业务仍待14号。
