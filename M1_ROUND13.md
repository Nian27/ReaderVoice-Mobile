# M1 Round 13 — 找到自由生成分叉的根因：prompt(prefill) 段从未被监督

日期：2026-09-16    状态：根因已定位并修复；DAgger 叠加训练中

## 1. 发现（本轮最重要的结果）

teacher-forced 的 cos 是**全序列平均**，掩盖了位置结构。按位置分解后：

    修复前 (student_12L_v2dagger):
        生成段 cos mean = 0.979 ~ 0.991   min 0.781
        **prompt 段 cos mean = 0.53 ~ 0.63   min -0.225**   <- 几乎不相关

        按位置：gen pos 0-49 mean=0.99826 ... 各段都 >0.97
                prompt_0 mean=0.55382 min=-0.22501
                prompt_100 mean=0.63432  prompt_200 mean=0.56485

## 2. 根因

`m1b_distill_slow.py` 的训练 loss 只监督 **slice(Tp-1, Tp-1+T)**，即生成段。
**prompt(prefill) 段从未被训练过。**

后果：student 自己 prefill 时写进 KV cache 的 prompt 段 K/V 与 teacher 不同，
生成阶段的 attention 输入就是错的 —— 这正是自由生成早期分叉的根因。

这也是用户当初那句警告的镜像：
    「不能直接从某个 teacher hidden/cache 开始 Student decode」
我让它自己 prefill 了，却**没有训练它 prefill 对**。

## 3. 修复

    m1b_distill_slow.py 增加 --no-full-sup（默认开启全序列监督）
    监督范围 slice(Tp-1, Tp-1+T) -> slice(0, Tp+T)
    teacher logits/hidden 现场前向计算（M0 只存了生成段）
    CE 仍只用生成段（prompt 段含非 semantic token）

## 4. 验证（同一位置分解）

                    修复前            修复后 (student_12L_fullsup)
    gen 段 mean     0.979~0.991       0.99958 ~ 0.99986
    prompt 段 mean  0.53~0.63   ❌    0.99994 ~ 0.99998   ✅
    prompt 段 min   -0.225      ❌    0.99706

**prompt 段 cos 从 0.53 拉到 0.99997。**

训练指标：cos_log 0.99970 / cos_hid 0.99829 / argmax 0.846（5000 步 / 795 s）

## 5. 自由生成跟踪

                   first_div  match@20  match@50  match_all
    v2 (仅 TF)        4.2       0.28      0.12      0.03
    v2dagger          8.2       0.62      0.40      0.08
    fullsup          10.4       0.57      0.25      0.05

早期跟踪改善（4.2 -> 10.4），但 50 帧处反而下降。
=> 两项修复互补，需要叠加：**全序列监督 + on-policy DAgger**。已启动。

## 6. 进行中

    m1b_dagger --init student_12L_fullsup.pt --steps 2000 --maxroll 64 --tf_prob 0.1 --lr 6e-5

## 7. 新增诊断工具

    scripts/m1_pos_profile.py   teacher-forced cos 按序列位置分解
    **这是本轮定位根因的关键工具；之前的全序列平均 cos 完全掩盖了问题。**

## 8. 纪律（第 5 条）

**任何『全序列平均』指标都必须按位置分解后再解释。**
0.99983 的全序列平均掩盖了 prompt 段 0.53 的严重缺陷。
这与前四条纪律同源：**指标的形式决定了你能不能看到问题**。
