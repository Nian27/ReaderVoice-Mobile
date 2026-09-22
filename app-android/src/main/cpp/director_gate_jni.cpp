#include <jni.h>
#include <chrono>
#include <memory>
#include <sstream>
#include <string>

#include "llm/llm.hpp"

namespace {
std::string runGate(const char* modelDir, const char* prompt, const char* backend) {
    const std::string backendString(backend);
    if (backendString != "cpu" && backendString != "opencl") {
        return "ERROR unsupported backend";
    }
    const auto started = std::chrono::steady_clock::now();
    std::ostringstream diag;
    diag << "modelDir=" << modelDir << "\n";
    std::unique_ptr<MNN::Transformer::Llm> llm(MNN::Transformer::Llm::createLLM(modelDir));
    diag << "createLLM_ok\n";
    diag << "dump_config=" << llm->dump_config().substr(0, 300) << "\n";
    llm->set_config(
        std::string("{\"backend_type\":\"") + backendString +
        "\",\"thread_num\":4,\"max_new_tokens\":32,\"async\":false,"
        "\"sampler_type\":\"greedy\",\"jinja\":{\"context\":{\"enable_thinking\":false}}}"
    );
    bool loadOk = llm->load();
    diag << "load=" << (loadOk ? "OK" : "FAIL") << " getLog=" << llm->getLog() << "\n";
    if (!loadOk) {
        return "LOAD_FAILED\n" + diag.str();
    }
    std::ostringstream output;
    llm->response(prompt, &output, nullptr, 0);
    auto* context = llm->getContext();
    int guard = 0;
    while (!llm->stoped() && context->gen_seq_len < 32 && guard < 128) {
        llm->generate(1);
        ++guard;
    }
    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started
    ).count();
    std::ostringstream report;
    report << diag.str() << "\nbackend=" << backendString << "\n"
           << "elapsed_ms=" << elapsedMs << "\n"
           << "status=" << static_cast<int>(context->status) << "\n"
           << "generated=" << static_cast<int>(context->gen_seq_len) << "\n"
           << "output=" << output.str() << "\n"
           << "log=" << llm->getLog();
    return report.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_readervoice_app_DirectorM1GateActivity_runDirectorGate(
    JNIEnv* env, jobject, jstring modelDir, jstring prompt, jstring backend
) {
    const char* modelDirChars = env->GetStringUTFChars(modelDir, nullptr);
    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    const char* backendChars = env->GetStringUTFChars(backend, nullptr);
    const std::string result = runGate(modelDirChars, promptChars, backendChars);
    env->ReleaseStringUTFChars(modelDir, modelDirChars);
    env->ReleaseStringUTFChars(prompt, promptChars);
    env->ReleaseStringUTFChars(backend, backendChars);
    return env->NewStringUTF(result.c_str());
}