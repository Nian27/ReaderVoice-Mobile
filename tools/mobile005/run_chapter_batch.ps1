# run_chapter_batch.ps1 — 真实章节批次：推送 prompts → 设备跑 → 拉回
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = '10.40.130.197:33879'
$root = 'E:\AndroidStudioProjects\ReaderVoiceMobile'
$b = "$root\runs\mobile_005_director_real\chapter_run"
$n = (Get-ChildItem "$b\prompts" -Filter '*.txt').Count
Write-Host "prompts=$n"

& $adb -s $dev shell 'rm -rf /data/local/tmp/ch; mkdir -p /data/local/tmp/ch'
& $adb -s $dev push "$b\prompts" /data/local/tmp/ch/ 2>&1 | Select-Object -Last 1
& $adb -s $dev shell 'if [ -d /data/local/tmp/ch/prompts ]; then mv /data/local/tmp/ch/prompts/* /data/local/tmp/ch/ 2>/dev/null; rmdir /data/local/tmp/ch/prompts 2>/dev/null; fi; ls /data/local/tmp/ch/*.txt | wc -l'

for ($i = 0; $i -lt $n; $i++) {
  $tag = '{0:D2}' -f $i
  $cmd = "cd /data/local/tmp/MNN && export LD_LIBRARY_PATH=/data/local/tmp/MNN P15_REPEAT=3 MNN_HEX_LAYER_HTP=0:23 P15_NOTUNE=1 && " +
         "./llm_demo model_rd/config_rd_hex_greedy.json /data/local/tmp/ch/$tag.txt 96 > /data/local/tmp/ch/out_$tag.txt 2>&1"
  & $adb -s $dev shell $cmd 2>&1 | Out-Null
  $c = (& $adb -s $dev shell "grep -c '^\{' /data/local/tmp/ch/out_$tag.txt") -replace '\s', ''
  Write-Host "  prompt_$tag answers=$c"
}
& $adb -s $dev shell 'cd /data/local/tmp/ch && rm -f /data/local/tmp/chout.tar && tar cf /data/local/tmp/chout.tar out_*.txt'
& $adb -s $dev pull /data/local/tmp/chout.tar "$b\chout.tar" 2>&1 | Select-Object -Last 1
Push-Location $b; tar xf chout.tar; Remove-Item chout.tar -ErrorAction SilentlyContinue; Pop-Location
Write-Host "out files: $((Get-ChildItem "$b\out_*.txt" -ErrorAction SilentlyContinue).Count)"
Write-Host "CHAPTER BATCH DONE"

