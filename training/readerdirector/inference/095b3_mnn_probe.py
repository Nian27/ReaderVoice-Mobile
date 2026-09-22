# -*- coding: utf-8 -*-
"""MOBILE-000B PC MNN 等价性冒烟：MNN.llm 加载 readerdirector-mnn 并跑 MENTION 任务查询。"""
import sys
import os

import MNN

MODEL_DIR = r"E:\AndroidStudioProjects\ReaderVoiceMobile\training\models\qwen3.5-base-mnn"
PROMPT = ("你是 ReaderDirector。找出下面文本中所有\u201c人物/可发声实体\u201d的提及（mention）："
          "人物、群体、职位/称谓、昵称、非人智能体等。\n"
          "只输出原文中逐字存在的片段（surface），不要创造、改写、省略或翻译名字\n"
          "每个 mention 输出 surface 和 type（PERSON/GROUP/ROLE/AGENT）\n"
          "没有则输出空列表\n"
          '输出 JSON: {"mentions": [{"surface": "...", "type": "..."}]}\n\n'
          "示例1：\n文本：雪千寻递过去一把精致的火铳，叶洋在一旁看着。\n"
          '输出：{"mentions": [{"surface": "雪千寻", "type": "PERSON"}, {"surface": "叶洋", "type": "PERSON"}]}\n\n'
          "文本：谭越没有犹豫，直接道：\u201c行。\u201d")


def main():
    print("MNN.llm api:", [x for x in dir(MNN.llm) if not x.startswith('_')][:15], flush=True)
    llm = MNN.llm.LLM()
    print("llm created", flush=True)
    import json as _json
    llm.set_config(_json.dumps({"llm_config_path": os.path.join(MODEL_DIR, "llm_config.json"),
                                "llm_model_path": os.path.join(MODEL_DIR, "llm.mnn"),
                                "tokenizer_path": os.path.join(MODEL_DIR, "tokenizer.mtok")}))
    llm.load()
    print("llm loaded", flush=True)
    resp = llm.response(PROMPT)
    print("RESP:", resp[:300], flush=True)
    llm.reset()
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
