import io, os
BS = chr(92); DL = chr(36); BR = chr(123)
ESC = BS + DL + BR
LIVE = DL + BR
base = r'E:/AndroidStudioProjects/ReaderVoiceMobile/app-android/src/main/java/com/readervoice/app'
for fn in ['RenderUnitBuilder.kt','RenderInstructionCompiler.kt','QwenBackendPolicy.kt']:
    p = os.path.join(base, fn)
    if not os.path.exists(p):
        print('MISSING ' + fn); continue
    s = io.open(p, encoding='utf-8').read()
    n = s.count(ESC)
    if n:
        s = s.replace(ESC, LIVE)
        io.open(p, 'w', encoding='utf-8').write(s)
    print('%-32s escaped=%d %s' % (fn, n, 'FIXED' if n else 'no-op'))
tdir = r'E:/AndroidStudioProjects/ReaderVoiceMobile/app-android/src/test/java/com/readervoice/app'
for fn in ['QwenBackendPolicyTest.kt','RenderInstructionCompilerTest.kt']:
    p = os.path.join(tdir, fn)
    print('%-32s %s' % (fn, ('EXISTS %d bytes' % os.path.getsize(p)) if os.path.exists(p) else 'MISSING'))