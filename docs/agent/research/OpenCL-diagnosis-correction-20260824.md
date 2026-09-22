# OpenCL "不可用" 真相 + RTF 差距归因（2026-08-24 修正诊断）

## 问题
- 真机 RTF≈3.2（cold，全 CPU），Nian27/CosyVoice3-MNN 同机型（Magic8 Pro/SM8850/Adreno830）
  热态 RTF 0.79-0.96，Flow GPU 0.8-1.1s。
- 之前诊断 "libMNN.so 是 OpenCL=OFF 构建 / Android16→17 Adreno 驱动不兼容" —— **误诊**。

## 铁证（CMakeCache.txt build-readervoice-android-arm64-16k）
```
MNN_OPENCL:BOOL=ON
MNN_LIBS:INTERNAL=MNN;MNN_Vulkan;MNN_CL;MNN_Express;MNNOpenCV;MNNAudio
```
构建带 OpenCL，但 MNN_SEP_BUILD=ON 把 CL 拆进独立 libMNN_CL.so。
主库 libMNN.so 里扫不到 OpenCLRuntime 符号是 SEP 拆分的正常结果，不是 OFF。

## 真正根因（两层）
1. **实体缺失**：APK extractNativeLibs=false（默认）→ nativeLibraryDir 是空目录，
   dlopen 找不到 libMNN_CL.so → OpenCL createSession null → rc=5。
   修复：app-android/build.gradle.kts 加 `packaging { jniLibs { useLegacyPackaging = true } }`
   （Nian27 官方 App 同款：README 已知问题3 也是 extractNativeLibs→useLegacyPackaging）。
2. **linker 不搜 nativeLibraryDir**：即便实体 .so 已提取，Android app 进程 dlopen
   默认路径不含 nativeLibraryDir；MNN SEP 主库运行时 dlopen("libMNN_CL.so") 仍失败。
   Nian27 0.8 能用是因为他的 libMNN.so 是 FULL 构建（CL 在主库内，无需 dlopen）。
   修复：flow JNI 初始化前先 `System.loadLibrary("MNN_CL")`（已在 CosyVoiceFlowNative 加入）。

## RTF 差距归因（3.2 vs 0.8）
| 阶段 | 我们（cold CPU） | Nian27（热态） |
|---|---|---|
| LLM | 1.08s（CPU 91 tok/s） | 1.5-2.0s（CPU + q_proj NPU） |
| Flow | 5.4s + resize 1.1s（CPU 6线程） | 0.8-1.1s（OpenCL GPU） |
| HiFT | 1.67s | 1.4-2.0s（CPU） |
| 总计 | 9.2s / 2.88s = 3.2 | 3.7-5.1s / 10s≈0.79-0.96 |

结论：**差距 5-6 倍全部来自 Flow 后端（CPU vs OpenCL）**，而 OpenCL 从不是
"驱动不兼容"——是 SEP 构建 + dlopen 缺实体/不搜路径两个工程问题，均已修复。
LLM 和 HiFT 我们本就在合理区间。

## NPU 现状（与 Nian27 一致）
- Nian27：SM8850 仅 q_proj 单算子 NPU（wall -5.94%）、HiFT 切12帧上HTP（约4.5s/窗）、
  QAIRT 全图 HTP 进行中但最终 PCM corr 0.835 未过门槛。
- 我们（audio8）同结论：HTP 性能达标但 PCM 质量门未过。A 路线仍为研究态。

## 待验证
- 装新 APK（含 MNN_CL 预加载）后重跑 OpenCL flow，预期 Flow inference 从 5.4s → 0.8-1.1s，
  hot RTF 掉到 ~1.0 以下。
