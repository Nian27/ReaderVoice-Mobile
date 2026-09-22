#!/system/bin/sh
PID=$(pidof com.cosyvoice.app.debug)
echo "PID=$PID"
echo "--- .so ---"
cat /proc/$PID/maps | grep -o "/[^ ]*\.so" | sort -u
echo "--- model files mmap ---"
cat /proc/$PID/maps | awk '{print $6}' | grep -E "\.(mnn|weight|bin|mtok)" | sort -u
echo "--- biggest mappings (>50MB) ---"
cat /proc/$PID/maps | while read line; do
  range=$(echo "$line" | cut -d" " -f1)
  start=${range%-*}; end=${range#*-}
  s=$((0x$start)); e=$((0x$end))
  kb=$(( (e - s) / 1024 ))
  if [ $kb -gt 50000 ]; then
    perms=$(echo "$line" | awk '{print $2}')
    path=$(echo "$line" | awk '{print $6}')
    echo "$kb kB  $perms  $path"
  fi
done | sort -rn | head -14
