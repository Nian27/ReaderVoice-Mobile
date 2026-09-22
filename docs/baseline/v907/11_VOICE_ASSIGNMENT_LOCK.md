# 11_VOICE_ASSIGNMENT_LOCK.md — v90.7 音色分配/锁定域（TASK-050 Domain 11）

**日期：2026-08-12** | 证据：L2（L5465-5492/L5767-5956/L8730-8811/L14007-14036）

## Purpose

音色分配链（least-used + 降级）、固定音色硬锁、解锁路径。

## assignVoice 完整链（L5767）

```text
1. duihuaVoicePool 动态标签精确匹配（排除已用+主角黑名单，L5782-5810）
2. 同性别同年龄段池 matchLevel=0（L5833-5845）
3. 同龄复用：角色记录顺序去重 + least-used（L5849-5881）
4. 同龄兜底：按序号最大（L5884-5904）
5. agePriority 年龄降级：男 男青年→少年→男童→男中年→男老年（女对称），特殊 系统→旁白（L5826-5830/L5908-5929）
6. 同性别终极兜底（L5932-5954）
7. 仍无 → null → 调用方 "duihua"/"default"
```

## Distinctiveness（§53 CONFIRMED_L2）

- `selectVoiceByGlobalRandom`(L5465)：Fisher-Yates 洗牌 → 按 matchLevel 升序 + **globalVoiceUsage 升序**（least-used）→ 选中后 usage++ + saveGlobalVoiceUsage
- **least-used 统计范围：全局跨书**（globalVoiceUsage.json，L1517 加载）——跨书共享语义可保留

## Voice Pool（§52，只作 Legacy behavior）

GENSHIN_CHARACTERS(L211-244)：主角男×20（"男主N"无补零）+ 女主×20（"女主NN"补零）+ 10 档×300 + 特殊×2×20 + 特殊 4 = **3084 个 voice tag**。ReaderVoice 不迁巨大固定池。

## Fixed Voice 硬锁（§54 CONFIRMED_L2）

判定（graphV907IsFixedVoiceRecord L8742）：
```text
ENABLE_FIXED_VOICE_HARD_LOCK=1
+ manualVoiceUnlock !== true（唯一解除标记）
+ 显式字段组（voiceLocked/fixedVoiceLocked/manualVoiceLocked/fixedVoice/lockVoice/isVoiceFixed/userFixedVoice）
+ 元数据字段组（fixedVoiceAt/Reason/LockTag）
+ fixedVoiceTag === voice
+ 男主/女主音色自动锁（ENABLE_MAIN_ROLE_VOICE_AUTO_LOCK=1）
+ 旧 usageCount=100 迁移（默认关闭）
```

优先级链（冲突时谁赢）：**manual fixed = 男主/女主 auto-lock > voice-age 更新（拒绝 L13496）> 临时换声（拒绝 L13641/13677）> merge（fixed 字段不迁移）> split（备份克隆可带回 fixed）**。

## 解锁（§55 CONFIRMED_L2）

**仅显式** `cancelFixedVoice`(L14023) → graphV907CancelFixedVoiceRecord(L8789) 删除 18 个固定字段 + manualVoiceUnlock=true；无任何自动解锁；重新 setFixedVoice 清 unlock 标记（L8782）。

## 已知问题

- 只有显式解锁——误锁（普通角色被分到男主1 音色）无自动纠正
- 全局 least-used 跨书统计换书后失真
- duihua 池优先于 GENSHIN 池可能打破档位均衡

## ReaderVoice mapping

**KEEP** 固定音色硬锁概念 + least-used 轮询语义；**REDESIGN**：锁模型简化为 VoiceBinding.lock_mode 三态（AUTO/USER_SELECTED/USER_LOCKED，v5 §44），建议增加"锁定期限/章节数"自动解锁选项并区分主角锁与用户锁；3084 池不迁移。

## Open questions

- usageCount（per-record）与 globalVoiceUsage（全局）双计数在 merge 后是否合并
- 主角自动锁的判定（voice tag 正则）在自定义音色场景的适用性
