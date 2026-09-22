#!/system/bin/sh
PID=$(pidof com.cosyvoice.app.debug:design)
echo "DESIGN_PID=$PID"
echo "--- status ---"
grep -E "VmRSS|RssAnon|RssFile|RssShmem|VmHWM" /proc/$PID/status
echo "--- 最大映射 (>20MB) ---"
cat /proc/$PID/maps | while read line; do
  range=$(echo "$line" | cut -d" " -f1)
  start=${range%-*}; end=${range#*-}
  kb=$(( (0x$end - 0x$start) / 1024 ))
  if [ $kb -gt 20000 ]; then
    echo "$kb kB  $(echo "$line" | awk '{print $2" "$6}')"
  fi
done | sort -rn | head -16
