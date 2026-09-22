# M0-2 RESULT — 36 spans + 3 registered voices + (text × voice) 交叉

日期：2026-09-16    状态：**smoke PASS**

## 1. 音色来源（自己上网找的，官方 Apache-2.0）

    model repo : Audio8/Audio8-TTS-Preview-0.6B-ONNX-INT4
    code repo  : https://github.com/Audio8-AI/Audio8_TTS   (Apache-2.0)
    音频来源   : docs/multilingual/reference/*.mp3  +  docs/cases/reference/*.mp3
    转写文本   : docs/data.json 的 reference.text 字段

用官方注册协议（onnx_runtime/arktts_runtime/registration.py）编 codes：
    decode -> mono -> resample 44100 -> pad 到 2048 倍数
    -> codec_encoder_fp16.onnx(audio[1,1,N] fp16) -> codes[10,T] -> 存 uint16

    已下载 17 个中文 reference 候选；注册了 3 个：

    voice_001  codes=(10,302)  14.02s  multilingual_zh_01
    voice_002  codes=(10,137)   6.36s  classical_polyphone_zh_01
    voice_003  codes=(10,126)   5.85s  classical_polyphone_zh_02

  voices/ 布局（同时满足官方 VoiceStore 与自定义命名需求）：
    voices/voice_00X/
      reference.wav          44100 mono float32（已 pad）
      reference_text.txt     转写
      reference_codes.npy    int64 [10,T]
      codes.npy              uint16 [10,T]   <- 官方 VoiceStore 读这个
      meta.json              含 reference_text，官方 VoiceLoader 读这个

## 2. 语料（36 spans）

    E:\小说\山河镇狱   bookA=第一卷 bookB=第二卷 bookC=第三卷
    每本 12 条 = 4 旁白 + 4 单人对话 + 4 混合；优先同章连续短段落
    spans_digest = e8832b8c29e4ed254c51a3fafa4b84e1158d4748a9f32230f76659c6cc7875b8

    可用池  bookA n485/d61/m40   bookB n1206/d88/m239   bookC n1251/d11/m43
    注意：段落长度下限必须 <=60（用 120 会把单人对话池清空）
    注意：m0_spans.json 首版被 json.dump 写成 GBK，已转 UTF-8；后续脚本必须显式 encoding='utf-8'

## 3. smoke 结果

    bookA-ch001-p009__voice_001   64帧  uniq=63/64  25.4s  peak=0.444
    bookA-ch001-p009__voice_002   64帧  uniq=62/64  22.5s  peak=0.487   <- 同文本换音色
    bookB-ch031-p000__voice_002   64帧  uniq=63/64  21.7s  peak=0.339   <- 同音色换文本

    平均 ~23s / sample（PC CPU，64 帧）=> 36 条约 14 分钟

## 4. 冻结的 sample schema

    prompt.npy     [11,T]  int64     官方 PromptBuilder 输出（含 prefix/semantic/suffix）
    positions.npy  [T]     int64
    x.npy          [T,11]  int64     每步 slow 输入列
    hidden.npy     [T,896] float32
    logits.npy     [T,4097] float32  未压缩
    semantic.npy   [T]     int64
    codes.npy      [T,10]  int64
    teacher.wav    44100 mono 16bit  教师音频（供听感/任务级 Gate）
    meta.json      含 book_id/chapter_id/source_span_id/span_kind/voice_id/reference_id/
                   reference_text/reference_audio_sha256/reference_codes_sha256/
                   teacher_model_digest/prompt_digest

## 5. 下一步

    M0-2 全量：36 spans x 多音色（按规格每个 voice >=8~10 条，含交叉）
    M0-3     book-level split_manifest.json（同书绝不跨 split）
    M0-5     dataset digest + manifest

## 6. 仍待办

- bookC 的 dialogue 池只有 11 条，抽样质量需人工确认
- voice_001 参考音频 14.02s（302 帧），prompt 会更长；如需与其他 voice 对齐可考虑截断
- 需补 DISTILLATION INVARIANT 的 4 条 voice-conditioning Gate（speaker similarity / 可区分性 /
  teacher-vs-student speaker cos / VoiceDesign 新 reference 可跟随）
