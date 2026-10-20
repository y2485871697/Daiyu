# 模型品牌图标来源与变换

用户明确授权从 RikkaHub 源码获取模型图标。此次只取十个品牌的 SVG 素材，未复制其图标组件、匹配器、网络加载器或其它 UI。

- 直接来源：rikkahub/rikkahub，固定提交 `2b633370f3948eb57b9f907e4fe767b5ff6e4536`。
- 原路径：`app/src/main/assets/icons/`；逐文件原名、SHA-256 与本地原件见 `sources.json` 和 `svg/`。
- RikkaHub 仓库许可为 AGPL-3.0；原文与本项目已保存的 `THIRD_PARTY/rikkahub/LICENSE` 字节相同，随包共用 `assets/licenses/AGPL-3.0.txt`。
- RikkaHub 的 `utils/AIIconMatcher.kt` 标明图标来源为 LobeHub Icons；SVG 中亦保留 lobe-icons 渐变标识。保留 LobeHub 的 MIT 原文 `LICENSE-LobeHub`（Copyright (c) 2023 LobeHub），取自 `lobehub/lobe-icons@8a1b2821bb768c484fe8da20c5e1838b6bf551a9/LICENSE`。这不是把 RikkaHub 中素材的导入 revision 冒认为该 LobeHub revision；素材字节固定于上述 RikkaHub 提交。
- 品牌名称与标志属于各自权利人；图标用于模型识别，不表示官方关联或背书。

代鱼改动：2026-10-21，将原 SVG 的 path、填充规则、渐变、透明度和 Kimi 分组变换转成 Compose ImageVector；Kimi 圆角矩形转等价路径。OpenAI/Grok 的 currentColor 使用代鱼当前主题色，其余保留品牌原色。未知模型回退代鱼通用图标。匹配规则与组件由代鱼实现，不引入在线取图、SVG 解码依赖或供应商 SDK。

`generate.py` 仅支持此批原件使用的 SVG 子集；`--check` 离线核对原件摘要及生成源码逐字一致，不下载、不执行上游脚本。生成目标为 `app/src/main/kotlin/app/daiyu/android/ui/icons/ModelBrandVectors.kt`。完整修改源码随代鱼 AGPL 项目公开，APK 的源码身份以具体构建提交为准。
