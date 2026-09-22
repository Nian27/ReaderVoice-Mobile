// voicedesign_jni.cpp - ReaderVoice VoiceDesign HTP full chain (in-app)
// text(离线 tokenize) -> prompt_emb -> 62x GraphB(prefill) -> self-driven AR -> 16 codes/frame
//   -> EOS -> decoder -> reference.wav
#include <jni.h>
#include <android/log.h>
#include <MNN/expr/Module.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include <memory>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <mutex>

using namespace MNN;
using namespace MNN::Express;
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VD", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VD", __VA_ARGS__)

namespace {

const int CAP = 16;      // codepred KV capacity
const int HD = 128;
const int NL = 28;       // talker layers
const int NKV = 8;       // talker kv heads
const int TKV = 768;     // talker KV capacity
const int CPNL = 5;      // codepred layers
const int CPKV = 8;

struct VdSession {
    std::shared_ptr<Module> mGB, mCH, mCE, mFE, mCPF, mCPS, mDEC;
    const float* lmW = nullptr;
    const float* cpW = nullptr;
    void* lmMap = nullptr; void* cpMap = nullptr;
    std::vector<float> tRopeC, tRopeS;    // (MAXPOS,128) talker merged mrope
    std::vector<float> cRopeC, cRopeS;    // (CAP,128) codepred rope
    int maxPos = 0;
    std::string backend = "cpu";
    std::mutex mu;
    unsigned long long seed = 123456789ull;
    std::string lastError;
};
static double nextU(unsigned long long& s) {
    s = s * 6364136223846793005ull + 1442695040888963407ull;
    return (double)((s >> 11) & ((1ull << 53) - 1)) / (double)(1ull << 53);
}
static int sampleTopK(const float* lg, int vocab, double temp, int topk, bool suppress, double u) {
    std::vector<std::pair<double,int>> v; v.reserve(vocab);
    for (int i = 0; i < vocab; i++) { if (suppress && i >= 2048 && i != 2150) continue; v.push_back({(double)lg[i]/temp, i}); }
    std::sort(v.begin(), v.end(), [](const std::pair<double,int>&a, const std::pair<double,int>&b){return a.first>b.first;});
    if ((int)v.size() > topk) v.resize(topk);
    double mx = v[0].first, s = 0; std::vector<double> pr(v.size());
    for (size_t i=0;i<v.size();i++){ pr[i]=exp(v[i].first-mx); s+=pr[i]; }
    for (size_t i=0;i<v.size();i++) pr[i]/=s;
    double acc=0;
    for (size_t i=0;i<v.size();i++){ acc+=pr[i]; if (u<=acc) return v[i].second; }
    return v.back().second;
}
static bool readF(const std::string& p, void* d, size_t n) {
    FILE* f = fopen(p.c_str(), "rb"); if (!f) return false;
    size_t r = fread(d, 1, n, f); fclose(f); return r == n;
}
static std::vector<float> readVec(const std::string& p, size_t n) {
    std::vector<float> v(n); if (!readF(p, v.data(), n*4)) { v.clear(); } return v;
}
static void writeWav16(const std::string& path, const std::vector<float>& x, int sr) {
    FILE* f = fopen(path.c_str(), "wb"); if (!f) return;
    int n = (int)x.size(); int db = n*2;
    unsigned char h[44] = {0};
    auto p32=[&](int o,unsigned v){h[o]=v&0xff;h[o+1]=(v>>8)&0xff;h[o+2]=(v>>16)&0xff;h[o+3]=(v>>24)&0xff;};
    auto p16=[&](int o,unsigned v){h[o]=v&0xff;h[o+1]=(v>>8)&0xff;};
    memcpy(h,"RIFF",4); p32(4,36+db); memcpy(h+8,"WAVE",4);
    memcpy(h+12,"fmt ",4); p32(16,16); p16(20,1); p16(22,1);
    p32(24,sr); p32(28,sr*2); p16(32,2); p16(34,16);
    memcpy(h+36,"data",4); p32(40,db);
    fwrite(h,1,44,f);
    std::vector<short> pcm(n);
    for (int i=0;i<n;i++){ float v=x[i]; if(v>1)v=1; if(v<-1)v=-1; pcm[i]=(short)lrintf(v*32767.0f); }
    fwrite(pcm.data(),2,n,f); fclose(f);
}
static void* mmapFile(const std::string& p, size_t bytes, void** out) {
    int fd = open(p.c_str(), O_RDONLY);
    if (fd < 0) return nullptr;
    void* q = mmap(nullptr, bytes, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (q == MAP_FAILED) return nullptr;
    *out = q;
    return q;
}
static std::shared_ptr<Module> loadM(const std::string& path, std::vector<std::string> i, std::vector<std::string> o,
                                     std::shared_ptr<Executor::RuntimeManager> rtm, std::string& err) {
    Module::Config c; c.shapeMutable = true;
    std::shared_ptr<Module> m(Module::load(i, o, path.c_str(), rtm, &c), Module::destroy);
    if (!m) err = "load failed: " + path;
    return m;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_readervoice_app_VoiceDesignEngine_nativeCreate(JNIEnv*, jobject) {
    return (jlong) new VdSession();
}
extern "C" JNIEXPORT void JNICALL Java_com_readervoice_app_VoiceDesignEngine_nativeRelease(JNIEnv*, jobject, jlong p) {
    if (p) delete reinterpret_cast<VdSession*>(p);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_readervoice_app_VoiceDesignEngine_nativeLastError(JNIEnv* e, jobject, jlong p) {
    VdSession* s = reinterpret_cast<VdSession*>(p); return e->NewStringUTF(s ? s->lastError.c_str() : "no session");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_readervoice_app_VoiceDesignEngine_nativeLoad(
        JNIEnv* env, jobject, jlong p, jstring jdir, jstring jbackend, jint maxPos, jstring jnativedir) {
    VdSession* s = reinterpret_cast<VdSession*>(p); if (!s) return JNI_FALSE;
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    const char* be  = env->GetStringUTFChars(jbackend, nullptr);
    std::string d(dir); s->backend = be; s->maxPos = maxPos;
    std::string nd;
    if (jnativedir) { const char* ndc = env->GetStringUTFChars(jnativedir, nullptr); nd = ndc; env->ReleaseStringUTFChars(jnativedir, ndc); }
    env->ReleaseStringUTFChars(jdir, dir); env->ReleaseStringUTFChars(jbackend, be);
    // DSP skel 必须能被 adsp 找到；命令行为此显式设 ADSP_LIBRARY_PATH，App 里同样需要
    if (!nd.empty()) { setenv("ADSP_LIBRARY_PATH", nd.c_str(), 1); LOGI("ADSP_LIBRARY_PATH=%s", nd.c_str()); }
    if (!d.empty() && d.back() != '/') d += "/";
    BackendConfig bc; bc.precision = BackendConfig::Precision_High; bc.power = BackendConfig::Power_High; bc.memory = BackendConfig::Memory_Normal;
    ScheduleConfig sc; sc.numThread = 4; sc.backendConfig = &bc;
    if (s->backend == "htp") sc.type = MNN_FORWARD_HEXAGON;
    else if (s->backend == "opencl") sc.type = MNN_FORWARD_OPENCL;
    else sc.type = MNN_FORWARD_CPU;
    std::shared_ptr<Executor::RuntimeManager> rtm(Executor::RuntimeManager::createRuntimeManager(sc), Executor::RuntimeManager::destroy);
    rtm->setHint(Interpreter::INIT_THREAD_NUMBER, 4);
    auto t0 = std::chrono::steady_clock::now();
    s->mGB  = loadM(d+"graphb28_v6_fp16.mnn", {"inputs_embeds","kv_k","kv_v","rope_cos","rope_sin","attn_mask","slot_mask"}, {"hidden_states","cur_k","cur_v"}, rtm, s->lastError);
    if (!s->mGB) return JNI_FALSE;
    s->mCH  = loadM(d+"codec_head_fp16.mnn", {"hidden_states"}, {"logits"}, rtm, s->lastError);
    s->mCE  = loadM(d+"codec_emb_fp16.mnn", {"codes"}, {"emb"}, rtm, s->lastError);
    s->mFE  = loadM(d+"frame_emb_fp16.mnn", {"codes"}, {"emb"}, rtm, s->lastError);
    s->mCPF = loadM(d+"codepred_prefill_fp16.mnn", {"past_hidden","code0_emb","rope_cos","rope_sin","slot_mask0","slot_mask1","attn_mask"}, {"hidden_last","kv_k_out","kv_v_out"}, rtm, s->lastError);
    s->mCPS = loadM(d+"codepred_step_fp16.mnn", {"token_emb","kv_k","kv_v","rope_cos","rope_sin","slot_mask","attn_mask"}, {"hidden_last","kv_k_out","kv_v_out"}, rtm, s->lastError);
    s->mDEC = loadM(d+"tokenizer_decoder_static_t300.mnn", {"codes"}, {"waveform"}, rtm, s->lastError);
    if (!s->mCH || !s->mCE || !s->mFE || !s->mCPF || !s->mCPS || !s->mDEC) return JNI_FALSE;
    s->lmW = (const float*)mmapFile(d+"lm_head_weight.f32", (size_t)15*2048*1024, &s->lmMap);
    s->cpW = (const float*)mmapFile(d+"codec_emb_weight.f32", (size_t)15*2048*2048, &s->cpMap);
    s->tRopeC = readVec(d+"talker_rope_cos.f32", (size_t)maxPos*HD);
    s->tRopeS = readVec(d+"talker_rope_sin.f32", (size_t)maxPos*HD);
    s->cRopeC = readVec(d+"codepred_rope_cos.f32", (size_t)CAP*HD);
    s->cRopeS = readVec(d+"codepred_rope_sin.f32", (size_t)CAP*HD);
    if (s->lmW == nullptr || s->cpW == nullptr || s->tRopeC.empty() || s->tRopeS.empty() || s->cRopeC.empty() || s->cRopeS.empty()) {
        s->lastError = "weight table missing in " + d; return JNI_FALSE;
    }
    double ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
    LOGI("loaded backend=%s in %.0fms", s->backend.c_str(), ms);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_readervoice_app_VoiceDesignEngine_nativeRun(
        JNIEnv* env, jobject, jlong p, jstring jdir, jstring jout, jint maxFrames) {
    VdSession* s = reinterpret_cast<VdSession*>(p);
    if (!s) return env->NewStringUTF("ERR no session");
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    const char* out = env->GetStringUTFChars(jout, nullptr);
    std::string d(dir), o(out);
    env->ReleaseStringUTFChars(jdir, dir); env->ReleaseStringUTFChars(jout, out);
    if (!d.empty() && d.back() != '/') d += "/";
    std::lock_guard<std::mutex> lk(s->mu);
    char buf[512];
    // prompt embeddings (L, 2048)
    FILE* f = fopen((d+"prompt_emb.f32").c_str(), "rb");
    if (!f) return env->NewStringUTF("ERR prompt_emb.f32 missing");
    fseek(f,0,SEEK_END); long nb = ftell(f); fseek(f,0,SEEK_SET);
    int L = (int)(nb / (2048*4));
    std::vector<float> pe(L*2048);
    if (fread(pe.data(), 4, pe.size(), f) != pe.size()) { fclose(f); return env->NewStringUTF("ERR prompt_emb short"); }
    fclose(f);
    const size_t TKVONE = (size_t)NL*NKV*TKV*HD;
    const size_t CPKVONE = (size_t)CPNL*CPKV*CAP*HD;
    std::vector<float> tK(TKVONE,0.f), tV(TKVONE,0.f), sm(TKV,0.f), am(TKV,0.f);
    std::vector<float> cpK(CPKVONE,0.f), cpV(CPKVONE,0.f), csm(CAP,0.f), cam(CAP,0.f);
    VARP vEmb=_Input({1,1,2048},NCHW,halide_type_of<float>());
    VARP vK=_Input({NL,1,NKV,TKV,HD},NCHW,halide_type_of<float>());
    VARP vV=_Input({NL,1,NKV,TKV,HD},NCHW,halide_type_of<float>());
    VARP vCos=_Input({1,1,1,HD},NCHW,halide_type_of<float>());
    VARP vSin=_Input({1,1,1,HD},NCHW,halide_type_of<float>());
    VARP vMask=_Input({1,1,1,TKV},NCHW,halide_type_of<float>());
    VARP vSlot=_Input({1,1,TKV,1},NCHW,halide_type_of<float>());
    auto gbStep = [&](const float* emb, int cp) -> std::vector<float> {
        memcpy(vEmb->writeMap<float>(), emb, 2048*4);
        memcpy(vK->writeMap<float>(), tK.data(), TKVONE*4);
        memcpy(vV->writeMap<float>(), tV.data(), TKVONE*4);
        memcpy(vCos->writeMap<float>(), &s->tRopeC[(size_t)cp*HD], HD*4);
        memcpy(vSin->writeMap<float>(), &s->tRopeS[(size_t)cp*HD], HD*4);
        for (int i=0;i<TKV;i++) am[i]=(i<=cp)?0.f:-1e4f;
        memcpy(vMask->writeMap<float>(), am.data(), TKV*4);
        sm.assign(TKV,0.f); sm[cp]=1.f; memcpy(vSlot->writeMap<float>(), sm.data(), TKV*4);
        std::vector<VARP> o = s->mGB->onForward({vEmb,vK,vV,vCos,vSin,vMask,vSlot});
        const float* h = o[0]->readMap<float>();
        std::vector<float> hb(h,h+2048);
        const float* ck = o[1]->readMap<float>(); const float* cv = o[2]->readMap<float>();
        for (int l=0;l<NL;l++) for (int hh=0;hh<NKV;hh++) {
            size_t off = ((size_t)(l*NKV+hh)*TKV + cp)*HD;
            memcpy(&tK[off], ck + ((size_t)(l*NKV+hh))*HD, HD*4);
            memcpy(&tV[off], cv + ((size_t)(l*NKV+hh))*HD, HD*4);
        }
        return hb;
    };
    auto t1 = std::chrono::steady_clock::now();
    std::vector<float> hidden;
    for (int cp=0; cp<L; cp++) hidden = gbStep(&pe[(size_t)cp*2048], cp);
    double prefillMs = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t1).count();
    LOGI("prefill %d tokens in %.0fms", L, prefillMs);
    // self-driven decode
    std::vector<float> lg(3072), lg2(2048), c0e(2048), cpH(1024), femb(2048);
    std::vector<int32_t> allCodes; int T=0, stopReason=-1; double decMs=0;
    VARP vCH=_Input({1,1,2048},NCHW,halide_type_of<float>());
    VARP vCE=_Input({1,1},NCHW,halide_type_of<int32_t>());
    VARP vPFa=_Input({1,1,2048},NCHW,halide_type_of<float>());
    VARP vPFb=_Input({1,1,2048},NCHW,halide_type_of<float>());
    VARP vPFc=_Input({1,1,2,HD},NCHW,halide_type_of<float>());
    VARP vPFs=_Input({1,1,2,HD},NCHW,halide_type_of<float>());
    VARP vPFm0=_Input({1,1,CAP,1},NCHW,halide_type_of<float>());
    VARP vPFm1=_Input({1,1,CAP,1},NCHW,halide_type_of<float>());
    VARP vPFam=_Input({1,1,2,CAP},NCHW,halide_type_of<float>());
    VARP vSTk=_Input({1,1,2048},NCHW,halide_type_of<float>());
    VARP vSTk_=_Input({CPNL,1,CPKV,CAP,HD},NCHW,halide_type_of<float>());
    VARP vSTv=_Input({CPNL,1,CPKV,CAP,HD},NCHW,halide_type_of<float>());
    VARP vSTc=_Input({1,1,1,HD},NCHW,halide_type_of<float>());
    VARP vSTs=_Input({1,1,1,HD},NCHW,halide_type_of<float>());
    VARP vSTm=_Input({1,1,CAP,1},NCHW,halide_type_of<float>());
    VARP vSTam=_Input({1,1,1,CAP},NCHW,halide_type_of<float>());
    VARP vFEi=_Input({1,16},NCHW,halide_type_of<int32_t>());
    for (int n=0;n<maxFrames;n++) {
        int cp = L + n;
        auto td0 = std::chrono::steady_clock::now();
        memcpy(vCH->writeMap<float>(), hidden.data(), 2048*4);
        std::vector<VARP> oc = s->mCH->onForward({vCH});
        memcpy(lg.data(), oc[0]->readMap<float>(), 3072*4);
        int code0 = sampleTopK(lg.data(), 3072, 0.9, 50, true, nextU(s->seed));
        if (code0 == 2150) { stopReason = 0; break; }
        { int32_t c=code0; memcpy(vCE->writeMap<int32_t>(), &c, 4); }
        std::vector<VARP> oe = s->mCE->onForward({vCE});
        memcpy(c0e.data(), oe[0]->readMap<float>(), 2048*4);
        // codepred prefill
        memcpy(vPFa->writeMap<float>(), hidden.data(), 2048*4);
        memcpy(vPFb->writeMap<float>(), c0e.data(), 2048*4);
        memcpy(vPFc->writeMap<float>(), &s->cRopeC[0], 2*HD*4);
        memcpy(vPFs->writeMap<float>(), &s->cRopeS[0], 2*HD*4);
        csm.assign(CAP,0.f); csm[0]=1.f; memcpy(vPFm0->writeMap<float>(), csm.data(), CAP*4);
        csm.assign(CAP,0.f); csm[1]=1.f; memcpy(vPFm1->writeMap<float>(), csm.data(), CAP*4);
        { std::vector<float> a2((size_t)2*CAP,0.f);
          for (int r=0;r<2;r++) for (int i=0;i<CAP;i++) a2[(size_t)r*CAP+i]=(i<=r)?0.f:-1e4f;
          memcpy(vPFam->writeMap<float>(), a2.data(), (size_t)2*CAP*4); }
        std::vector<VARP> op = s->mCPF->onForward({vPFa,vPFb,vPFc,vPFs,vPFm0,vPFm1,vPFam});
        memcpy(cpH.data(), op[0]->readMap<float>(), 1024*4);
        memcpy(cpK.data(), op[1]->readMap<float>(), CPKVONE*4);
        memcpy(cpV.data(), op[2]->readMap<float>(), CPKVONE*4);
        int32_t codes[16]; codes[0]=code0;
        for (int o2=0;o2<2048;o2++){ double acc=0; const float* w=&s->lmW[(size_t)o2*1024]; for(int i=0;i<1024;i++) acc+=(double)w[i]*cpH[i]; lg2[o2]=(float)acc; }
        for (int g=0;g<15;g++) {
            codes[g+1] = sampleTopK(lg2.data(), 2048, 0.9, 50, false, nextU(s->seed));
            if (g==14) break;
            const float* e = &s->cpW[((size_t)g*2048 + codes[g+1])*2048];
            int ccp = 2+g;
            memcpy(vSTk->writeMap<float>(), e, 2048*4);
            memcpy(vSTk_->writeMap<float>(), cpK.data(), CPKVONE*4);
            memcpy(vSTv->writeMap<float>(), cpV.data(), CPKVONE*4);
            memcpy(vSTc->writeMap<float>(), &s->cRopeC[(size_t)ccp*HD], HD*4);
            memcpy(vSTs->writeMap<float>(), &s->cRopeS[(size_t)ccp*HD], HD*4);
            csm.assign(CAP,0.f); csm[ccp]=1.f; memcpy(vSTm->writeMap<float>(), csm.data(), CAP*4);
            for (int i=0;i<CAP;i++) cam[i]=(i<=ccp)?0.f:-1e4f;
            memcpy(vSTam->writeMap<float>(), cam.data(), CAP*4);
            std::vector<VARP> os_ = s->mCPS->onForward({vSTk,vSTk_,vSTv,vSTc,vSTs,vSTm,vSTam});
            memcpy(cpH.data(), os_[0]->readMap<float>(), 1024*4);
            memcpy(cpK.data(), os_[1]->readMap<float>(), CPKVONE*4);
            memcpy(cpV.data(), os_[2]->readMap<float>(), CPKVONE*4);
            for (int o2=0;o2<2048;o2++){ double acc=0; const float* w=&s->lmW[(size_t)(g+1)*2048*1024 + (size_t)o2*1024]; for(int i=0;i<1024;i++) acc+=(double)w[i]*cpH[i]; lg2[o2]=(float)acc; }
        }
        memcpy(vFEi->writeMap<int32_t>(), codes, 16*4);
        std::vector<VARP> of = s->mFE->onForward({vFEi});
        memcpy(femb.data(), of[0]->readMap<float>(), 2048*4);
        hidden = gbStep(femb.data(), cp);
        decMs += std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-td0).count();
        allCodes.insert(allCodes.end(), codes, codes+16);
        T++;
    }
    if (T == 0) return env->NewStringUTF("ERR no frames generated");
    // decoder: pad/trim to 300 frames
    const int NFR = 300, SPF = 1920, SR = 24000;
    std::vector<int32_t> packed((size_t)16*NFR, 0);
    for (int t=0;t<NFR;t++){ int src = t<T?t:T-1; for(int g=0;g<16;g++) packed[(size_t)g*NFR+t]=allCodes[(size_t)src*16+g]; }
    VARP vDec=_Input({1,16,NFR},NCHW,halide_type_of<int32_t>());
    memcpy(vDec->writeMap<int32_t>(), packed.data(), packed.size()*4);
    auto tdec = std::chrono::steady_clock::now();
    std::vector<VARP> od = s->mDEC->onForward({vDec});
    double decdMs = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-tdec).count();
    const float* wv = od[0]->readMap<float>();
    size_t wl = (size_t)T*SPF; if (wl > 576000) wl = 576000;
    std::vector<float> wave(wv, wv+wl);
    writeWav16(o, wave, SR);
    double mn=1e9,mx=-1e9; size_t nan=0;
    for (float v : wave){ if(!std::isfinite(v)){nan++;continue;} if(v<mn)mn=v; if(v>mx)mx=v; }
    snprintf(buf,sizeof(buf),"OK steps=%d stop=%s prefill=%.0fms decode=%.0fms decoder=%.0fms wav=%zu samples (%.2fs) finite=%s min=%.4f max=%.4f",
             T, stopReason==0?"EOS":"MAXFRAMES", prefillMs, decMs, decdMs, wl, (double)wl/SR,
             nan==0?"yes":"NO", mn, mx);
    LOGI("%s", buf);
    return env->NewStringUTF(buf);
}
