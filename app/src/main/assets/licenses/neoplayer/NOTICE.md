# NeoPlayer video progress adaptation

Portions of the video-only progress drawing are adapted from NextPlayerCloud/NeoPlayer (mpvEx fork).
- Revision: 4151a45f862550a91b7a8efe35a6b19841242d48
- Source: https://github.com/NextPlayerCloud/NeoPlayer/blob/4151a45f862550a91b7a8efe35a6b19841242d48/app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/components/Seekbar.kt
- Upstream license: Apache-2.0, complete unmodified text in LICENSE.
- Local target: app/src/main/kotlin/app/daiyu/android/ui/media/ChatMediaPreviewVideoProgress.kt
- Modifications: extracted the wave path and fixed drawing parameters; adapted to Daiyu video state, native Material3 slider gestures/accessibility, cancellable animations; omitted chapters, timers, and A-B loops. No global slider or player dependency was imported.

The source file begins with package/import declarations, without a file-level copyright notice; no author ownership is inferred. This is Daiyu's provenance record, not an upstream NOTICE or endorsement.
