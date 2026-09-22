# -*- coding: utf-8 -*-
import sys, io, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.path.insert(0, 'inference')
from voice_binding_seeds import deterministic_bind, _surface_to_candidate
from voice_event_binding import VoiceEventBinding

cands = [{'role': 'C0', 'name': '张三'}, {'role': 'C1', 'name': '李四'}, {'role': 'C2', 'name': '王五'}]
class FakeEvent:

    def __init__(self, t, eid=None):

        self.event_type = t
        self.event_id = eid

for txt in ['李四的声音突然变成了老人', '张三听见李四的声音突然变了', '李四模仿王五的声音说话']:
    b, app = deterministic_bind(FakeEvent('VOICE_OVERRIDE_SET'), txt, cands)
    print(f'{txt!r}: applied={app} owner={b.voice_state_owner_role_id} ref={b.reference_role_id} status={b.binding_status}')
    # 手动正则检查
    m = re.search(r'([\u4e00-\u9fa5A-Za-z·]{2,8})的(声音|嗓音|声线|音色|腔调|口音|嗓子)(?:突然|忽然|竟|却|已经|又)?(变|换|恢复|改变|模仿|压低|消失|被处理|被调)', txt)
    print('  pattern1 match:', m.group(0) if m else None, '| owner surface:', m.group(1) if m else None)
    print('  surface->cand:', _surface_to_candidate(m.group(1), cands) if m else None)