#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>

#include "llm/llm.hpp"

namespace {

// M1: 有状态 QwenEngine native session。一次 load，多次 generate；
// cancel 是协作式（atomic 标志 + generate(1) 逐 token 循环检查），绝不杀线程。
struct QwenEngineSession {
    std::unique_ptr<MNN::Transformer::Llm> llm;
    std::string backend = "cpu";
    bool loaded = false;
    std::atomic<bool> cancelRequested{false};
    std::mutex generateMutex;
};

inline QwenEngineSession* fromPtr(jlong ptr) {
    return reinterpret_cast<QwenEngineSession*>(ptr);
}

/**
 * MOBILE-005 / M3：把取消真正打进 native 生成循环。
 *
 * MNN 的 AR decode 循环（speculative_decoding/generate.cpp:44）每个 token 前检查
 * `mContext->status == USER_CANCEL || INTERNAL_ERROR`，并在 `param.timeout_ms` 超限时置 TIMEOUT。
 * 因此这里**不需要改 MNN**：把状态置成 USER_CANCEL，native 会在下一个 token 边界退出，
 * 而不是等整轮 generate 返回（这正是"Kotlin withTimeout 不够"的那一层）。
 */
inline void requestNativeCancel(QwenEngineSession* s) {
    if (s == nullptr || s->llm == nullptr) return;
    s->llm->requestCancel();          // MOBILE-005: fork 新增的公开入口（AR 循环下一 token 边界退出）
}

inline bool engineCanceled(QwenEngineSession* s) {
    if (s == nullptr || s->llm == nullptr) return true;
    const auto st = s->llm->status();
    return st == MNN::Transformer::LlmStatus::USER_CANCEL ||
           st == MNN::Transformer::LlmStatus::TIMEOUT ||
           st == MNN::Transformer::LlmStatus::INTERNAL_ERROR;
}

std::string backendOf(const QwenEngineSession& s) {
    return s.backend;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_readervoice_app_QwenEngine_nativeCreate(JNIEnv*, jobject) {
    auto* session = new QwenEngineSession();
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_readervoice_app_QwenEngine_nativeRelease(JNIEnv*, jobject, jlong ptr) {
    delete fromPtr(ptr);
}

JNIEXPORT jboolean JNICALL
Java_com_readervoice_app_QwenEngine_nativeLoad(
    JNIEnv* env, jobject, jlong ptr, jstring modelDir, jstring backend
) {
    auto* s = fromPtr(ptr);
    if (s == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->generateMutex);
    const char* modelDirChars = env->GetStringUTFChars(modelDir, nullptr);
    const char* backendChars = env->GetStringUTFChars(backend, nullptr);
    const std::string dir(modelDirChars);
    const std::string be(backendChars);
    env->ReleaseStringUTFChars(modelDir, modelDirChars);
    env->ReleaseStringUTFChars(backend, backendChars);
    if (be != "cpu" && be != "hexagon" && be != "opencl") {
        return JNI_FALSE;
    }
    // ── G5: Hexagon 运行环境（必须在 MNN Runtime / Interpreter 创建之前设置）──
    //  1) ADSP_LIBRARY_PATH: DSP 通过它定位 libMNN_htpops_skel.so；APK 的 native 库
    //     与 skel 同目录，这里用 dladdr 取得该目录，不依赖 Java 侧传参。
    //  2) MNN_HEX_LA_FORCE=1: linear-attention 走 HTP（与 CLI 已验证路径一致）。
    //  3) 显式清除 MNN_HEX_NO_GATE_BARRIER: gate completion barrier 默认开启。
    static std::once_flag sEnvOnce;
    std::call_once(sEnvOnce, []() {
        Dl_info info;
        if (dladdr(reinterpret_cast<void*>(&Java_com_readervoice_app_QwenEngine_nativeLoad), &info) != 0 &&
            info.dli_fname != nullptr) {
            std::string self(info.dli_fname);
            const size_t slash = self.find_last_of('/');
            if (slash != std::string::npos) {
                const std::string libDir = self.substr(0, slash);
                const std::string adsp = libDir + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp";
                setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
            }
        }
        setenv("MNN_HEX_LA_FORCE", "0", 1);
        // ★ 显式把 LinearAttention 钉在 CPU（M2-3 的专用隔离开关）：
        //   prefill 实测 CPU LA 857 ms 远快于 DSP GDR 1.48 s；
        //   而 decode(L=1) 时若 LA 被 hexagon 侧接受，会走 HTP 的 LA 路径 —— 本轮
        //   App A/B 要单独回答"LA-on-HTP 对 decode 是帮助还是拖累"，因此显式 LA_OFF。
        setenv("MNN_HEX_LA_OFF", "1", 1);
        // ── P16-FIX/P16-3 之后的执行域（与 CLI 已验证路径一致）──
        //  层白名单 = 0:23（全 24 层允许 HTP）。P15 时期的 12:23 是 RoPE bug 未修时的默认，
        //  P16-FIX 后 0:23 既最准（logits cos 0.994986，文本与 CPU 逐字一致）又最快。
        //  ★ 不设 MNN_HEX_LA_FORCE：该开关是 M2-1 隔离探针用的，会把 LinearAttention 强行
        //    推给 HTP。实测 prefill 阶段 CPU LA 857 ms 远快于 DSP GDR 1.48 s，
        //    "backend=hexagon" 必须是 Dense→HTP / LinearAttention→CPU 的混合结构。
        //  不加白名单时：全部 24 层都会尝试上 HTP，且 op->name()==nullptr 的 op 会绕过
        //  BLOCK 过滤逃逸到 HTP —— 实测命令组数从 ~98k 涨到 >420k（约 4.3 倍），
        //  同时破坏数值正确性（见 report.md 的隔离机制一节）。
        setenv("MNN_HEX_LAYER_HTP", "0:23", 1);
        unsetenv("MNN_HEX_NO_GATE_BARRIER");
    });
    __android_log_print(ANDROID_LOG_INFO, "QwenEngine",
                        "MNN Hexagon build: hexdebug-20260914 + P16-FIX(rope) + P16-3(repack); "
                        "gateBarrier=enabled; layerHtp=0:23; laForce=off; requestedBackend=%s",
                        be.c_str());
    s->backend = be;
    s->llm.reset(MNN::Transformer::Llm::createLLM(dir));
    if (s->llm == nullptr) {
        return JNI_FALSE;
    }
    // G5: 与 CLI 已验证配置对齐（runs/.../model_rd/config_rd_hex_greedy.json）
    //   precision/memory = low 是走 W4A16 int4 HTP 路径的必要条件；缺失会退化到
    //   "asymmetric int4 scale is not supported by W4A16 HTP path, fallback to fp16
    //    convolution (broken path)"，实测生成吞吐会慢几个数量级。
    s->llm->set_config(
        std::string("{\"backend_type\":\"") + be +
        "\",\"thread_num\":4,\"precision\":\"low\",\"memory\":\"low\","
        "\"max_new_tokens\":96,\"async\":false,"
        "\"sampler_type\":\"greedy\",\"temperature\":0,\"top_k\":1,\"top_p\":0.9,"
        "\"jinja\":{\"context\":{\"enable_thinking\":false}}}"
    );
    const bool ok = s->llm->load();
    s->loaded = ok;
    __android_log_print(ANDROID_LOG_INFO, "QwenEngine", "QwenEngine load: requestedBackend=%s result=%s",
                        be.c_str(), ok ? "ok" : "fail");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// P16-APP: App 内性能剖析开关（必须在 Runtime/Interpreter 创建前设置）。
// 为什么要它：shell（llm_demo）与 App 对同一 Director 请求的 decode 给出【相反】结论
// （App: CPU 15 ms/tok < HTP 62 ms/tok；shell: CPU 240 ms/tok > HTP 94 ms/tok），
// 在口径统一之前不能用 shell 的 per-op 数字做架构决策 ⇒ 必须在 App 内测。
//   MNN_OP_TIMING            → host 侧逐 op 计时（MNN_PRINT 走 logcat）
//   MNN_HEX_PERF_LOG/DSP     → 文件式落盘到 dumpDir（App 的 logcat 不可靠）
JNIEXPORT void JNICALL
Java_com_readervoice_app_QwenEngine_nativeSetProfiling(JNIEnv* env, jobject, jboolean on) {
    if (on == JNI_FALSE) {
        unsetenv("MNN_OP_TIMING");
        return;
    }
    setenv("MNN_OP_TIMING", "1", 1);
    const char* d = getenv("M23_LOGITS_DUMP_DIR");
    std::string dir = d ? std::string(d) : std::string("/data/local/tmp");
    if (!dir.empty() && dir.back() != '/') dir += '/';
    setenv("MNN_HEX_PERF_LOG", (dir + "hex_perf.log").c_str(), 1);
    setenv("MNN_HEX_DSP_PROFILE_LOG", (dir + "hex_dsp.log").c_str(), 1);
    __android_log_print(ANDROID_LOG_INFO, "QwenEngine", "profiling on: opTiming=1 perf=%s",
                        (dir + "hex_perf.log").c_str());
}

// P16-APP: 让 MNN llm 的 logits/input_embeds 转储落到 App 可写目录（files/），
// 供 App↔shell 同输入逐字节对拍。App 的 CWD 是 "/"，默认写 CWD 会静默失败。
JNIEXPORT void JNICALL
Java_com_readervoice_app_QwenEngine_nativeSetDumpDir(JNIEnv* env, jobject, jstring dir) {
    if (dir == nullptr) {
        unsetenv("M23_LOGITS_DUMP_DIR");
        return;
    }
    const char* c = env->GetStringUTFChars(dir, nullptr);
    if (c != nullptr) {
        setenv("M23_LOGITS_DUMP_DIR", c, 1);
        __android_log_print(ANDROID_LOG_INFO, "QwenEngine", "logits dump dir = %s", c);
        env->ReleaseStringUTFChars(dir, c);
    }
}

JNIEXPORT void JNICALL
Java_com_readervoice_app_QwenEngine_nativeCancel(JNIEnv*, jobject, jlong ptr) {
    auto* s = fromPtr(ptr);
    if (s != nullptr) {
        s->cancelRequested.store(true);
    }
}

JNIEXPORT void JNICALL
Java_com_readervoice_app_QwenEngine_nativeReset(JNIEnv*, jobject, jlong ptr) {
    auto* s = fromPtr(ptr);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->generateMutex);
    s->cancelRequested.store(false);
    if (s->llm != nullptr && s->loaded) {
        s->llm->reset();
    }
}

JNIEXPORT jstring JNICALL
Java_com_readervoice_app_QwenEngine_nativeGenerate(
    JNIEnv* env, jobject, jlong ptr, jstring prompt, jint maxTokens
) {
    auto* s = fromPtr(ptr);
    if (s == nullptr) {
        return env->NewStringUTF("ERROR session null");
    }
    std::lock_guard<std::mutex> lock(s->generateMutex);
    if (!s->loaded || s->llm == nullptr) {
        return env->NewStringUTF("ERROR not loaded");
    }
    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    const std::string promptStr(promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    s->cancelRequested.store(false);
    std::ostringstream output;
    // P16-APP: 独立墙钟测 prefill / decode —— 用于核对 MNN 的 *_us 计数
    // （CPU 配置下 App 报 15 ms/token，而同一 App 在 HTP 配置下的 CPU 常驻 op 就要 37 ms/token，
    //  两者自相矛盾，必须用墙钟判定哪个是真的）
    auto w0 = std::chrono::steady_clock::now();
    s->llm->response(promptStr, &output, nullptr, 0);
    auto w1 = std::chrono::steady_clock::now();
    const long long prefWallUs =
        std::chrono::duration_cast<std::chrono::microseconds>(w1 - w0).count();
    auto* context = s->llm->getContext();
    const int cap = maxTokens > 0 ? maxTokens : 64;
    int guard = 0;
    while (!s->llm->stoped() && context->gen_seq_len < cap && guard < cap * 4) {
        if (s->cancelRequested.load()) {
            return env->NewStringUTF("CANCELED");
        }
        s->llm->generate(1);
        ++guard;
    }
    auto w2 = std::chrono::steady_clock::now();
    const long long decWallUs =
        std::chrono::duration_cast<std::chrono::microseconds>(w2 - w1).count();
    // G5 验收: 把生成结果写入日志（UI 读取易受 Activity 实例重建影响，日志是可靠证据）
    const std::string outStr = output.str();
    auto* ctx = s->llm->getContext();
    __android_log_print(ANDROID_LOG_INFO, "QwenEngine",
                        "generate done: cap=%d gen_seq_len=%d chars=%zu text=[%.800s]", cap,
                        context->gen_seq_len, outStr.size(), outStr.c_str());
    // P16-APP gate: 指标同时落日志，便于脚本化读取（App CPU↔HTP prefill/decode 对比）
    __android_log_print(ANDROID_LOG_INFO, "QwenEngine",
                        "metrics backend=%s load_us=%lld prefill_us=%lld decode_us=%lld gen_seq_len=%d",
                        s->backend.c_str(), (long long)ctx->load_us, (long long)ctx->prefill_us,
                        (long long)ctx->decode_us, ctx->gen_seq_len);
    // 独立墙钟（与 *_us 对拍；token 数用 gen_seq_len 折算 ms/token）
    __android_log_print(ANDROID_LOG_INFO, "QwenEngine",
                        "wall backend=%s prefill_wall_us=%lld decode_wall_us=%lld gen_seq_len=%d "
                        "decode_us_per_tok=%.2f wall_us_per_tok=%.2f",
                        s->backend.c_str(), prefWallUs, decWallUs, ctx->gen_seq_len,
                        ctx->gen_seq_len > 0 ? (double)ctx->decode_us / ctx->gen_seq_len : 0.0,
                        ctx->gen_seq_len > 0 ? (double)decWallUs / ctx->gen_seq_len : 0.0);
    return env->NewStringUTF(outStr.c_str());
}

/**
 * MOBILE-005 / M3：**带 deadline 的生成**（App 内整章运行的 decider 用它）。
 *
 * 返回前缀化结果，Kotlin 侧据此走状态机：
 *   "OK:<text>" 正常 | "TIMEOUT" 超时（native 已 USER_CANCEL 中断）| "CANCELED" 外部取消
 *
 * deadline 在每个 token 之间检查（wall-clock），并同时置 native 状态 —— 双保险：
 * 即使某次 forward 偏慢，也不会在整轮结束前才发现超时。
 */
JNIEXPORT jstring JNICALL
Java_com_readervoice_app_QwenEngine_nativeGenerateDeadline(
    JNIEnv* env, jobject, jlong ptr, jstring prompt, jint maxTokens, jlong timeoutMs
) {
    auto* s = fromPtr(ptr);
    if (s == nullptr || !s->loaded || s->llm == nullptr) {
        return env->NewStringUTF("ERROR not loaded");
    }
    std::lock_guard<std::mutex> lock(s->generateMutex);
    const char* pc = env->GetStringUTFChars(prompt, nullptr);
    const std::string promptStr(pc);
    env->ReleaseStringUTFChars(prompt, pc);

    s->cancelRequested.store(false);
    // 每轮开始前把状态复位（上一轮的 USER_CANCEL/TIMEOUT 会让本轮直接失败）
    s->llm->resetStatus();
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    std::ostringstream output;

    // prefill（此调用若卡住，只能靠 Kotlin 侧看门狗判 ENGINE_STUCK）
    s->llm->response(promptStr, &output, nullptr, 0);
    if (std::chrono::steady_clock::now() > deadline) {
        requestNativeCancel(s);
        return env->NewStringUTF("TIMEOUT");
    }
    auto* context = s->llm->getContext();
    const int cap = maxTokens > 0 ? maxTokens : 64;
    int guard = 0;
    bool timedOut = false;
    while (!s->llm->stoped() && context->gen_seq_len < cap && guard < cap * 4) {
        if (s->cancelRequested.load()) {
            requestNativeCancel(s);
            return env->NewStringUTF("CANCELED");
        }
        if (std::chrono::steady_clock::now() > deadline) {
            timedOut = true;
            requestNativeCancel(s);       // ★ 让 native AR 循环立刻在下一个 token 边界退出
            break;
        }
        s->llm->generate(1);
        ++guard;
    }
    __android_log_print(ANDROID_LOG_INFO, "QwenEngine",
                        "generate_deadline done: cap=%d gen_seq_len=%d timeout_ms=%lld timedOut=%d",
                        cap, context->gen_seq_len, (long long)timeoutMs, timedOut ? 1 : 0);
    if (timedOut) return env->NewStringUTF("TIMEOUT");
    const std::string outStr = output.str();
    return env->NewStringUTF(("OK:" + outStr).c_str());
}

/** 外部取消：置 native 状态 + 标志，使当前生成在 token 边界退出。 */
JNIEXPORT void JNICALL
Java_com_readervoice_app_QwenEngine_nativeCancelGeneration(JNIEnv*, jobject, jlong ptr) {
    auto* s = fromPtr(ptr);
    if (s == nullptr) return;
    s->cancelRequested.store(true);
    requestNativeCancel(s);
}

JNIEXPORT jstring JNICALL
Java_com_readervoice_app_QwenEngine_nativeGetBackend(JNIEnv* env, jobject, jlong ptr) {
    auto* s = fromPtr(ptr);
    if (s == nullptr) return env->NewStringUTF("unknown");
    return env->NewStringUTF(s->backend.c_str());
}
JNIEXPORT jstring JNICALL
Java_com_readervoice_app_QwenEngine_nativeGetMetrics(JNIEnv* env, jobject, jlong ptr) {
    auto* s = fromPtr(ptr);
    if (s == nullptr || s->llm == nullptr) return env->NewStringUTF("{}");
    auto* c = s->llm->getContext();
    std::ostringstream m;
    m << "{\"backend\":\"" << s->backend << "\","
      << "\"load_us\":" << c->load_us << ","
      << "\"prefill_us\":" << c->prefill_us << ","
      << "\"decode_us\":" << c->decode_us << ","
      << "\"gen_seq_len\":" << c->gen_seq_len << "}";
    return env->NewStringUTF(m.str().c_str());
}

} // extern "C"
