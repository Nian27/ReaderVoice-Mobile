# P0 CLOSURE — 设备侧「声音重复」根因与修复

日期：2026-09-16
状态：**CLOSED** —— 用户确认修复后音质正常

## 结论

设备侧 AR/codec 链路**从来没有坏过**。听到的「音节重复」是我在做性能插桩时引入的回归。

## 根因

audit8_jni.cpp 的 nativeSynthesizeText **从不重置 AR KV cache**：
slowCache.init(24) / fastCache.init(A8_FAST_LAYERS) 只在 nativeInit 里执行过一次。

后果有两条：
1. 同一进程内第 2/3 次合成会继承上一次的 KV cache
2. 我加的 A-B-A 实验让 nativePerfSplit 在合成**之前**跑，
   它内部调 slowStepRead() 23 次 -> 每次 appendDelta() -> 往 slowCache 灌 23 条垃圾 delta
   -> AR 从无意义状态起步 -> semantic 振荡词汇表收窄 -> 听感=音节重复

## 证据（同一句文本、同一 slow.bin）

                       frames  sem uniq      判读
    perf_f3 (bug 前)     62    56/62 (90%)   healthy
    f4ab    (bug 中)     64    40/64 (62%)   中段锁在 {1925,2499,2715,1939,656,144,3664} 振荡
    clean   (修复后)     62    56/62 (90%)   healthy

    修复后 vs perf_f3：前 62 帧 semantic 逐帧相同 = 62/62
                       前 62 帧 10 路 codes 全同  = 62/62   <- 逐位一致

## 修复

nativeSynthesizeText 入口增加：

    g_eng->slowCache.init(24);
    g_eng->fastCache.init(A8_FAST_LAYERS);
    LOGI("SYNTHTEXT cache reset (slow=24 fast=%d)", (int)A8_FAST_LAYERS);

日志已确认生效。

## 对蒸馏计划的影响

**ExecPlan 风险 #1（设备侧 correctness 未收口）解除。**

同时 P0-2/P0-3/P0-4（teacher/device 逐帧 divergence 定位）**不再需要**——
device codes 与 bug 前逐位相同，没有 divergence 可找。

## 听感 A/B（同一份设备 codes，两个 codec）

    T1_deviceCodes_PCcodec.wav               设备 AR codes -> PC 官方 FP16 codec
    T2_deviceCodes_deviceCodec_restored.wav  设备 AR codes -> 设备官方 T64 codec

    PC codec    : peak=0.4879  rms=0.0605
    device codec: peak=0.4536  rms=0.0556
    cos(PC, device) = 0.8640
    （设备 WAV 131072 样本/64 帧，PC 126976/62 帧 —— 设备把最后 2 帧补零）

结果：用户确认音质正常。

## 教训（写进纪律）

**性能插桩必须先证明自己不改被测系统的行为。**
本次 instrumentation 直接修改了被测对象的状态（AR KV cache），
并把结果误报成「QNN 声音不对」，进而让路线判断被错误信息带偏。

新增纪律：任何插桩/marker 引入后，必须先用与基线逐位一致的输出证明行为未变，
再采信其测量结果。
