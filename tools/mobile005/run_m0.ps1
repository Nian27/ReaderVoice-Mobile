# run_m0.ps1 — 逐条驱动 M0 选择题探针（宿主侧循环，可断点续跑）
param([string]$Dev = 'A3TE025B03003242', [int]$Reps = 3, [int]$From = 0, [int]$To = 24)
$adb = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
for ($i = $From; $i -le $To; $i++) {
  $tag = '{0:D2}' -f $i
  $exists = (& $adb -s $Dev shell "test -s /data/local/tmp/m0/out_$tag.txt && echo yes || echo no") -replace '\s',''
  if ($exists -eq 'yes') { Write-Host "skip $tag (exists)"; continue }
  $cmd = "cd /data/local/tmp/MNN && export LD_LIBRARY_PATH=/data/local/tmp/MNN P15_REPEAT=$Reps MNN_HEX_LAYER_HTP=0:23 P15_NOTUNE=1 && " +
         "./llm_demo model_rd/config_rd_hex_greedy.json /data/local/tmp/m0/prompt_$tag.txt 96 > /data/local/tmp/m0/out_$tag.txt 2>&1"
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  & $adb -s $Dev shell $cmd 2>&1 | Out-Null
  $n = (& $adb -s $Dev shell "grep -c '^\{' /data/local/tmp/m0/out_$tag.txt") -replace '\s',''
  Write-Host ("prompt_$tag done in {0:n1}s, answers={1}" -f $sw.Elapsed.TotalSeconds, $n)
}
Write-Host "M0 RUN DONE"
