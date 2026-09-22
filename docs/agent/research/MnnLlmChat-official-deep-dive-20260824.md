# MnnLlmChat 官方 App 深挖（2026-08-24）

来源：https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat

## 定位
MNN 官方全功能多模态 LLM Android App（v0.8.3）：文本→文本/图像/音频、语音输入、扩散模型。仅 arm64-v8a。完全端侧。

## 与我项目相关的核心结论

### 1. 官方 LLM 集成姿势（cosy_llm_jni 的直接参照）
- Kotlin 层：`LlmSession.kt`（app/src/main/java/com/alibaba/mnnllm/android/llm/）
  - `initNative(configPath, history, mergedConfigStr, configJsonStr) → Long` 创建 native 会话
  - `submitNative(nativePtr, prompt, keepHistory, progressListener)` 流式生成，**progressListener.onProgress 返回 Boolean = 协作取消**（对应 AGENTS 不变量 10：Native cooperative cancel，禁止杀线程）
  - 返回
 HashMap：prompt_len/decode_len/vision_us/audio_us/prefill_us/decode_us（性能指标）
  - `updateConfig/updateSystemPrompt/updateMaxNewTokens/setKeepHistory` 运行时热改
  - `setAudioDataListener` → `SetWavformCallback`
- C++ 层：`llm_session.h` + `llm_mnn_jni.cpp`（app/src/main/cpp/）
  - `mls::LlmSession(modelDir, config_json, extra_config, history)` 封装 `MNN::Transformer::Llm`
  - `Response(prompt, on_progress)` 回调式流式，回调返回 bool 继续/停止
  - **`SetWavformCallback(std::function<bool(const float*, size_t, bool)>)` + `enableAudioOutput(bool)`：LLM 引擎原生输出 PCM 波形**（LLM_SUPPORT_AUDIO 构建选项的消费端，CosyVoice LLM→音频直达）
  - `getLlm()` 暴露底层 Llm 指针（benchmark 用）
- 配置：llm_config.json 字段 = llm_model/llm_weight/backend_type/thread_num/precision/memory/use_mmap/system_prompt/sampler(temperature/topK/topP/minP/max_new_tokens)/jinja
  - 与我们的 config-cpu-cosyvoice-ras.json 一致（同字段 + cosyvoice_ras 采样器专用项：cosyvoice_speech_offset=151924, speech_token_count=6561, eos_token_id=158486, ras_window=10, ras_tau=0.1）
  - 官方提供 mergeJson：custom_config.json 覆盖默认 config，PROTECTED_KEYS=llm_model/llm_weight/backend_type（防空串覆盖）

### 2. QNN/HTP 官方姿势（A 路线钥匙，与 audio8tts-mnn 结论互证）
`QnnModule.kt`（com/alibaba/mnnllm/android/qnn/）：
- SOC→Hexagon 映射表：SM8750→V79, SM8650→V75, SM8550→V73, SM8475→V69, SM8450→V69, SM8350→V68
- 加载流程：`Os.setenv("ADSP_LIBRARY_PATH", path)` + `Os.setenv("LD_LIBRARY_PATH", path)` → load libQnnHtp.so → libQnnSystem.so → libQnnHtp${V}Skel + Stub
- QNN 模型：backend_type=qnn 时 visual_model="visual_qnn_${middleName}.mnn"（middleName 如 "69_v79"）
- **我们的 SM8850/V81 不在官方表里**——官方表滞后，但我们已在 SM8850+V81 上实证 HTP 契约（audio8tts-mnn：QAIRT 2.48 offline context + V81 skel）

### 3. 16KB 页（与我们结论一致）
app/build.gradle: `arguments "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"`（NDK r27）。官方所有 Android 构建强制 16KB 页对齐——我们没有例外，MNN 3.6.1 prebuilt 已是 16KB 版。

### 4. 多模态构建矩阵（MNN 侧）
build_64.sh 参数：`-DMNN_LOW_MEMORY=true -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true -DMNN_BUILD_LLM=true -DMNN_SUPPORT_TRANSFORMER_FUSE=true -DMNN_ARM82=true -DMNN_USE_LOGCAT=true -DMNN_OPENCL=true -DLLM_SUPPORT_VISION=true -DMNN_BUILD_OPENCV=true -DMNN_IMGCODECS=true -DLLM_SUPPORT_AUDIO=true -DMNN_BUILD_AUDIO=true -DMNN_BUILD_DIFFUSION=ON -DMNN_SEP_BUILD=OFF`
- 音频相关：LLM_SUPPORT_AUDIO + MNN_BUILD_AUDIO（libMNNAudio.so，我们已链）
- 模型大文件用 .weight 分片方案（noCompress: weight/part1..10/mnn/bin/mdl/msc）+ FileSplitter 合并（大模型 OTA 的官方姿势）

### 5. MNN AUDIO 框架（audio.hpp，Enrollment/speech-tokenizer 消费端）
MNN::AUDIO 提供：load/save(hann/hamming 窗)/mel_spectrogram/fbank(80 mel, 400fft, 160hop, 0.97 preemphasis)/whisper_fbank/n_fft 400——libMNNAudio.so 的公开接口。

### 6. 官方 App 模块
- mnn_tts（frameworks/mnn_tts/android，master 不可见，可能是内部/未合并仓库）
- model_downloader（frameworks/model_downloader/android）
- com/k2fsa/sherpa（sherpa 语音）

## 对我们的可操作性
1. **LLM 波形直出可行**：enableAudioOutput + SetWavformCallback 说明 MNN 3.6.1 Llm 支持 audio output（我们 cosy_llm_jni 生成 speech tokens 后仍需通过 flow 解码，波形直出用于 enrollment/QC 直接可听）
2. **backend_type 热切换**：官方 QNN 路径 = config 改 backend_type + 环境变量 + Skel/Stub 加载——A 路线（flow QNN 化）完全同构
3. 协作取消协议与 llm_stream_buffer 流式缓冲可作为 cosy_llm_jni 改进模板
