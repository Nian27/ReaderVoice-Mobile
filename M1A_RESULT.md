# M1-A — Student 初始化完成（12/8/6/4L）

日期：2026-09-16    状态：**DONE**

## 权重来源（走 base model safetensors，不走 ONNX）

    E:\AndroidStudioProjects\audio8tts-mnn\assets\local\Audio8-TTS-Preview-0.6b\model.safetensors
    1,202,342,528 bytes  BF16  226 tensors

本地已存在，无需下载。来源音频/权重均为 Apache-2.0。

config.json 佐证：n_layer=24 n_head=14 n_local_heads=2 head_dim=64 dim=896
                 intermediate_size=4864  **rope_base=1000000**（与 F0-B 实测一致）
                 attention_qkv_bias=true  attention_o_bias=false  qk_norm=false
                 tie_word_embeddings=true

## 层映射（按用户规格）

    first   = N_TEACHER // n - 1
    spacing = (23 - first) // (n - 1)        末层固定 teacher 23

    12L  [1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23]   422.21M params   844.4 MB
     8L  [2, 5, 8, 11, 14, 17, 20, 23]                 362.56M params   725.1 MB
     6L  [3, 7, 11, 15, 19, 23]                        332.74M params   665.5 MB
     4L  [5, 11, 17, 23]                               302.91M params   605.8 MB
    teacher 24L 全量 601.16M params   每层 14.912M

产物：runs/audio8-m1/student_{12,8,6,4}L.safetensors + m1a_report.json

## 冻结项已验证

    NON-LAYER tensors identical to teacher: PASS
      (embeddings / norm / codebook_embeddings / fast_embeddings / fast_norm / fast_output)
    layer 0 shapes OK:  attention.wqkv.weight [1152,896]  feed_forward.w1.weight [4864,896]

=> 第一版 Student 与 teacher 的**外部契约完全相同**（token ID 输入，embedding 在模型内部），
   且 voice-conditioning 协议（reference text + reference codes 进 prompt）未被改动。
   符合 DISTILLATION INVARIANT。

## 每层 8 个张量（slow）

    layers.N.attention.wo.weight        [896, 896]
    layers.N.attention.wqkv.weight      [1152, 896]      (14*64 + 2*64 + 2*64 = 1152)
    layers.N.attention.wqkv.bias        [1152]
    layers.N.attention_norm.weight      [896]
    layers.N.feed_forward.w1.weight     [4864, 896]
    layers.N.feed_forward.w2.weight     [896, 4864]
    layers.N.feed_forward.w3.weight     [4864, 896]
    layers.N.ffn_norm.weight            [896]

## 训练环境（已确认可用）

    E:\AndroidStudioProjects\ReaderVoiceMobile\training\envs\qwen3tts\.venv\Scripts\python.exe
    torch 2.11.0+cu128   cuda True   NVIDIA GeForce RTX 4060 Ti 16GB
    safetensors OK

    （系统 Python310 无 torch；cosyvoice3-distill-lab 的两个 env 也没有；只有训练 venv 有）

## 下一步 M1-B

    12L smoke overfit: 16~32 samples, 3 train voice
    训练路径（关键）：prompt.npy -> Student 自己 prefill -> teacher-forced decode -> 逐帧比对
    Gate: G-content / G-clone / G-separation / G-open-set

    需要的组件：
      1. 可训练的 ArkTTS 前向（对照 base_modeling_arktts.py 实现 slow 部分）
      2. teacher-forced 数据加载器（读 runs/audio8-m0/samples/*）
      3. 蒸馏 loss: KL(logits) + hidden MSE + CE(semantic)
      4. 四个 Gate 的评测脚本
