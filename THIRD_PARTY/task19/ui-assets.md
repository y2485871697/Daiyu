# T04 / T05：图片与图标

核验基线ae6e834，子任务5d59471a已回收。原核验基线ae6e834的tracked范围没有迁入鲸鱼PNG/猫PNG；文本矢量ic_launcher.xml是新工程临时D图标。非文本穷举不包含候选/旧树或APK，也不把用户自研猫图标/时间戳/加载/操作栏重新送审原创。

## T04鲸鱼女仆图片

旧资源目录声明六张PNG：pet_sprite、cupboard_bg、rice_bowl、rice_grain、rice_pot、rice_spoon，随件LICENSE/NOTICE写adapted from AcidGr/dsh-web-whale-maid，MIT，版权DeepSeek Harness Desktop Pet Authors。只读目录/声明不能代替冻结提交字节绑定。

子任务读取主源快照7389222e2176603b197076ab3823e44d85547b3b：
https://github.com/AcidGr/dsh-web-whale-maid/blob/7389222e2176603b197076ab3823e44d85547b3b/LICENSE
https://github.com/AcidGr/dsh-web-whale-maid/tree/7389222e2176603b197076ab3823e44d85547b3b/assets

已核MIT文字及同名资产结构；这个参照commit不是已经证明的旧图导入版本。17号需交实际选定旧发布SHA、每张原/目标path/size/SHA256、上游对应源path/hash或裁切/缩放/重编码转换链、原LICENSE/NOTICE。17号已交本地源码清单`/workspace/daiyu-coordination/task-17/source-manifest.json`，source_revision为`3196270e361c82cc1f399218d914ec49de0d83cc`；其中WhaleMaid*.kt、WhaleMaidSettingsScreen.kt等是源码路径/字节数/摘要，不是旧冻结发布f001fb82的六张PNG清单，代码SHA256不能替代图片摘要。其用户确认自研P24/P29/P30/P31的scope不扩大到外源图片或整个历史自有源码。图片原件/来源绑定缺口保持。用户自研UI不重新审原创。

用户自研宠物行为与外源图片分开保留。此时只可称上游许可参照已核，不能称六图hash来源绑定完成。

### af18af3 随包收口更新（不覆盖上述历史基线）

当前工作树已经实际存在 `app/src/main/assets/whale-maid/` 六张同名 PNG 及完整 `LICENSE` / `NOTICE`，不再沿用“当前未迁入图片”的早期描述。其随件文字仍声明 adapted from AcidGr/dsh-web-whale-maid、MIT、DeepSeek Harness Desktop Pet Authors。仓内可读来源资料没有这六张图的**逐图上游原 path / hash / 导入 revision / 转换链**；既有 `7389222e...` 仍只是参照，不将其猜作实际导入版本，源码清单的 SHA 也不代替 PNG SHA。

当前逐图本地 path、bytes、SHA-256 由显式维护入口 `scripts/verify_license_bundle.py --stage` 写入随包 `assets/licenses/bundle.sha256.json` 的 `local_image_evidence`，供后续源码树和指定 APK 原字节检查；这仅绑定本地制品，不补造上游源摘要。原 LICENSE/NOTICE 保持在图片目录，另原样随 `assets/licenses/whale-maid/` 集中保留。状态和缺项见 `THIRD_PARTY/license-bundle/source-index.md`；未查实际 APK 时不宣称 APK 已核。

## T05 MoneyBag02

用户确认 MoneyBag02 为其 fork 后原创，历史存储位置、文件名或样式不证明第三方图标库归属。本项不再列为未知上游缺口；保留现有矢量、描边、缓存、余额显示与动画调用，不新增库或要求重画。实际第三方品牌图标、社区图标仍保留各自来源和许可。
