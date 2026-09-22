# run_m0_v2.ps1 — 用 M3 口径修正后的 fixture 重跑 M0 选择题探针
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = 'A3TE025B03003242'
$root = 'E:\AndroidStudioProjects\ReaderVoiceMobile'
$m0 = "$root\runs\mobile_005_director_real\m0_protocol_probe"

Write-Host "[1/4] push new prompts + fixture"
& $adb -s $dev shell 'rm -rf /data/local/tmp/m0; mkdir -p /data/local/tmp/m0'
& $adb -s $dev push "$m0\fixture.jsonl" /data/local/tmp/m0/ 2>&1 | Select-Object -Last 1
& $adb -s $dev push "$m0" /data/local/tmp/m0/ 2>&1 | Select-Object -Last 1
& $adb -s $dev shell 'if [ -d /data/local/tmp/m0/m0_protocol_probe ]; then mv /data/local/tmp/m0/m0_protocol_probe/* /data/local/tmp/m0/ 2>/dev/null; rmdir /data/local/tmp/m0/m0_protocol_probe 2>/dev/null; fi; ls /data/local/tmp/m0/prompt_*.txt | wc -l'

Write-Host "[2/4] run 25 prompts x 3 reps on device"
for ($i = 0; $i -le 24; $i++) {
  $tag = '{0:D2}' -f $i
  $cmd = "cd /data/local/tmp/MNN && export LD_LIBRARY_PATH=/data/local/tmp/MNN P15_REPEAT=3 MNN_HEX_LAYER_HTP=0:23 P15_NOTUNE=1 && " +
         "./llm_demo model_rd/config_rd_hex_greedy.json /data/local/tmp/m0/prompt_$tag.txt 96 > /data/local/tmp/m0/out_$tag.txt 2>&1"
  & $adb -s $dev shell $cmd 2>&1 | Out-Null
  $n = (& $adb -s $dev shell "grep -c '^\{' /data/local/tmp/m0/out_$tag.txt") -replace '\s', ''
  Write-Host "  prompt_$tag answers=$n"
}

Write-Host "[3/4] pull outputs"
& $adb -s $dev shell 'cd /data/local/tmp/m0 && rm -f /data/local/tmp/m0out.tar && tar cf /data/local/tmp/m0out.tar out_*.txt'
& $adb -s $dev pull /data/local/tmp/m0out.tar "$m0\m0out.tar" 2>&1 | Select-Object -Last 1
Push-Location $m0; tar xf m0out.tar; Remove-Item m0out.tar -ErrorAction SilentlyContinue; Pop-Location
Write-Host "  out files: $((Get-ChildItem "$m0\out_*.txt").Count)"

Write-Host "[4/4] judge"
node "$root\tools\mobile005\judge_selection.js" "$m0\fixture.jsonl" $m0
