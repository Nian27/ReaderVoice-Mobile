#!/system/bin/sh
PID=$(pidof com.cosyvoice.app.debug:design)
if [ -z "$PID" ]; then PID=$(pidof com.cosyvoice.app.debug); fi
echo "DESIGN_PID=$PID"
echo "--- cosy 相关 .so（隔离验证：不应出现 llm/flow/hift） ---"
cat /proc/$PID/maps 2>/dev/null | grep -o "/data/app/[^ ]*/lib/arm64/lib[^ ]*\.so" | sed "s|.*/||" | sort -u
echo "--- 内存 ---"
cat /proc/$PID/status 2>/dev/null | grep -E "VmRSS|RssAnon|VmHWM"
echo "--- CPU 时间 ---"
cat /proc/$PID/stat 2>/dev/null | awk '{print "  utime="$14" stime="$15}'
