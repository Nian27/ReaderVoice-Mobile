#!/system/bin/sh
PID=$(pidof com.cosyvoice.app.debug:design)
if [ -z "$PID" ]; then PID=$(pidof com.cosyvoice.app.debug); fi
echo "PID=$PID"
echo "--- graphb 相关的 mmap ---"
cat /proc/$PID/maps 2>/dev/null | grep -i graphb | head -8
echo "--- 所有 voicedesign 下的 file-backed 映射 ---"
cat /proc/$PID/maps 2>/dev/null | grep voicedesign | awk '{print $6}' | sort -u | head -12
echo "--- 当前 anon ---"
grep -E "RssAnon|VmRSS" /proc/$PID/status 2>/dev/null
