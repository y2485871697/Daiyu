# VirtualDisplay owner 来源与许可

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

## 原创归属与输入标识

用户明确确认本模块为其 fork 后原创，以版权拥有者身份授权本项目保留、修改和集成。历史存储位置和包名不作为他人原创的证据。
输入归档 SHA-256：`6d7b1fe8f58909ef64f4687f8f2bd46e32082c5febd65698e9bdac1ee60e61ab`；历史版本标识：`2a7e7ab92b98da5a3711611017021441e7a8053c`。版本标识只定位既有输入，不将其当作第三方上游提交。

## 当前实现

- 32 个生产 Java：`app/src/main/java/app/daiyu/android/vd/runtime/`。
- 既有及新增测试：`app/src/test/java/app/daiyu/android/vd/runtime/`。
- Anchor：`app/src/main/kotlin/app/daiyu/android/vd/VirtualDisplayAnchorActivity.kt`。
- 保留 owner 的 handoff、provenance、release 算法和回归；宿主包名、组件、URI、API 26 适配及 no-replay 保护不改变原创归属。
- 不引入另一应用框架、旧客户端或预编译 owner dex。

## 随附许可

保留同目录 `LICENSE` 中既有的 PolyForm Noncommercial License 1.0.0 原文及 Required Notice；本次名称和来源归属纠正不改写许可文本，不为第三方依赖重新授权。应用根许可不替代各独立组件随附许可。
