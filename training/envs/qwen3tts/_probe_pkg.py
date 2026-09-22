import sys
print("PY", sys.version.split()[0])
for m in ["torch","transformers","onnx","qwen_tts","soundfile","numpy"]:
    try:
        mod = __import__(m)
        print(m, getattr(mod, "__version__", "?"))
    except Exception as e:
        print(m, "MISSING", e)
