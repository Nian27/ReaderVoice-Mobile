#!/system/bin/sh
PID=$(pidof com.cosyvoice.app.debug:design)
echo "PID=$PID"
echo "--- smaps_rollup ---"
cat /proc/$PID/smaps_rollup 2>/dev/null | grep -E "Rss|Anon|Pss|Private"
echo "--- anon 大块按名字聚合（>=5MB）---"
cat /proc/$PID/smaps 2>/dev/null | awk '
  /^[0-9a-f]+-[0-9a-f]+ / { split($1,a,"-"); sz=(strtonum("0x" a[2])-strtonum("0x" a[1]))/1048576; name=$6; if (name=="") name="[anon]"; cur=name; rss=0 }
  /^Rss:/ { rss=$2 }
  /^$|^VmFlags/ { if (rss>=5) { agg[cur]+=rss } rss=0 }
  END { for (k in agg) printf "  %8.0f MB  %s\n", agg[k]/1024, k }
' | sort -rn | head -14
echo "--- 统计所有 [anon:xxx] 系列 ---"
grep -oE "\[anon:[a-z_-]+" /proc/$PID/maps 2>/dev/null | sort | uniq -c | sort -rn | head -10
