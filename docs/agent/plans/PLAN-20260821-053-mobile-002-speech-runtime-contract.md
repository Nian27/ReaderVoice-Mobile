# PLAN-20260821-053 — MOBILE-002：Speech Runtime Contract

## Goal

在接入任何 TTS 模型前，冻结 `RenderUnit + VoiceProfile → SpeechRoute → TTSEngine → AudioAsset` 的 Android 运行时边界；CosyVoice 为目标正式引擎，Audio8 仅为未验证候选。

## Current Verified Facts

- `RENDERUNIT_V1_SCHEMA.md` 已冻结 R3：角色只绑定 VoiceProfile，SpeechRoute 选引擎；RenderUnit 不放引擎私有参数。
- `VOICE_PROFILE_SCHEMA.md` 已冻结：CosyVoice runtime assets 与 Audio8 registration assets 必须隔离；共同身份层只能是 reference wav/text。
- CosyVoice3-MNN 真机基线存在，但本仓库 `tts-cosyvoice/` 目前只有抽取规范，没有 Android runtime/JNI 实现。
- Audio8 PC 的 RTF/内存 Gate FAIL，Android ORT Spike 未做；任何产品 Router 不得选择 Audio8。

## Non-goals

- 不复制 CosyVoice JNI/模型资产，不执行模型下载，不引入 ORT，不生成真实 PCM。
- 不改 RenderUnit v1 / VoiceProfile v1 字段，不把 Audio8 伪装成已验证 engine。

## Invariants

- RenderUnit 纯数据；角色不绑定 engine；AudioAsset 必绑 `voice_revision_id`；TTS 不在 UI 线程执行；播放 P0；用户锁不被路由覆盖。

## Scope

- `app-android/`：SpeechRoute、TTSEngine、CosyVoice adapter boundary、Audio8 unavailable placeholder 和单测。
- `docs/DECISIONS.md`、`docs/PROJECT_STATE.md`、本计划、`MEMORY.md`。

## Milestones

- M1：纯 Kotlin Contract + Router unit tests，Audio8 不可用时不可被选中。
- M2.0：CosyVoice 源 ABI 与可搬资产审计；先形成不可变源快照，再开始抽取。
- M2：CosyVoice runtime 抽取 Spike，固定 RenderUnit → WAV → cache；独立 PCM Gate。
- M3：Audio8 Android ORT Spike；通过质量/内存/RTF Gate 后才把 route capability 切为 AVAILABLE。

## Progress

- M1 ✅ 2026-08-21：`TTSEngine`、`SpeechRoute`、CosyVoice adapter boundary、Audio8 unavailable placeholder 与 Router unit tests 已实现。`app-android:testDebugUnitTest :app-android:assembleDebug --offline` PASS；这只证明 Contract，不证明任何 TTS runtime。
- M2.0 ✅ 2026-08-22：源工作树的 `.git` 无法被 Git 解析，不能声称 Git snapshot；改以 `runs/mobile_002_cosyvoice_extraction/source_snapshot.md` 记录源码/预编译 JNI SHA-256 和 ABI。`llvm-nm` 实测四个 JNI 库导出 `Java_io_legado_app_cosy_*`，与源 Kotlin 的 `com.cosyvoice.app` 不一致；抽取层必须保留 `io.legado.app.cosy` JNI bridge，产品 adapter 留在 `com.readervoice.app`。
- M2 ⏳
- M3 ⏳

## Decisions

- D1：SpeechRoute 是运行时决策，不写回角色身份，也不改变冻结 RenderUnit。
- D2：第一版路由按叙事身份固定：`NARRATOR|UNKNOWN_SPEAKER → Audio8`，`CHARACTER → CosyVoice3-MNN`；不按角色重要性或设备性能动态切换。
- D3：引擎能力默认 fail-closed；Audio8 未通过 Android Gate 时旁白 route 返回明确 unavailable，不回退为 CosyVoice。
- D4：Audio8 与 CosyVoice 共用 canonical voice identity，但 adapter assets 和 cache keys 分离。

## Validation

- Router：具名角色固定 CosyVoice；旁白/未知固定 Audio8；Audio8 未可用时旁白返回明确 unavailable，不能改道 CosyVoice。
- 代码：`app-android` unit test + debug APK build PASS。
- M2/M3 分别要求真实 WAV/PCM Gate；接口或 build 不能替代。

## Rollback

- 删除 app runtime contract 文件即可；无 Book Package、模型或用户 VoiceProfile 迁移。

## Artifacts

- `runs/mobile_002_speech_runtime/report.md`。

## Open Issues

- CosyVoice JNI symbol/package drift 已定位：预编译库 ABI 固定为 `io.legado.app.cosy`；不能把 native declaration 直接置于 `com.readervoice.app`。
- 源工作树 Git 元数据失效；本轮使用 SHA-256 snapshot 代替 Git commit，后续若修复源 worktree 再补 Git revision。
- ReaderVoice 与现有 Cosy debug app 是不同 Android UID，不能共享私有 `files/cosyvoice3-mnn/model`；产品需经 SAF/受控导入安装模型，真机 Spike 可另做一次性 debug provisioning，但不得把它当产品安装路径。
- Audio8 Android ORT 的内存、RTF、registration 和质量均未验证。

## Handoff

- CosyVoice 抽取只完成 ABI bridge 与固定 PCM Gate，不扩展业务功能；Audio8 按独立 feasibility plan 050 做 Android ORT Gate，不进入产品 route。Scheduler/UI 必须等待 Director、Audio8、CosyVoice 的各自 Gate，而不是用空接口推进。
