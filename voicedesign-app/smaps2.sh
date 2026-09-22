#!/system/bin/sh
PID=$(pidof com.cosyvoice.app.debug:design)
echo "PID=$PID"
# 用 awk 逐条记录 header 的 name，遇到 Rss: 就累加
cat /proc/$PID/smaps 2>/dev/null | awk '
  /^[0-9a-f]+-[0-9a-f]+ / {
    name=$6; if (name=="") name="[anon-plain]";
    if (name ~ /scudo/) bucket="[anon:scudo]";
    else if (name ~ /dalvik/) bucket="[anon:dalvik*]";
    else if (name ~ /bionic_alloc/) bucket="[anon:bionic_alloc_small_objects]";
    else if (name ~ /cfi/) bucket="[anon:cfi]";
    else if (name ~ /thread|stack_and_tls/) bucket="[anon:thread/stack]";
    else if (name ~ /^\[/) bucket=name;
    else bucket="[file]" name;
  }
  /^Rss:/ { rss=$2; if (rss>0) tot[bucket]+=rss; next }
  END { for (k in tot) printf "%9.1f MB  %s\n", tot[k]/1024, k }
' | sort -rn | head -16
