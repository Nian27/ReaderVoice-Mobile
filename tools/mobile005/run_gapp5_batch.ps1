# run_gapp5_batch.ps1 — G_APP5 批次：推送 prompts → 设备跑 8×3 → 拉回
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = 'A3TE025B03003242'
$root = 'E:\AndroidStudioProjects\ReaderVoiceMobile'
$b = "$root\runs\mobile_005_director_real\gapp5_batch"

Write-Host "[1/3] push"
& $adb -s $dev shell 'rm -rf /data/local/tmp/g5; mkdir -p /data/local/tmp/g5'
& $adb -s $dev push "$b" /data/local/tmp/g5/ 2>&1 | Select-Object -Last 1
& $adb -s $dev shell 'if [ -d /data/local/tmp/g5/gapp5_batch ]; then mv /data/local/tmp/g5/gapp5_batch/* /data/local/tmp/g5/ 2>/dev/null; rmdir /data/local/tmp/g5/gapp5_batch 2>/dev/null; fi; ls /data/local/tmp/g5/prompt_*.txt | wc -l'

Write-Host "[2/3] run 8 prompts x 3 reps"
for ($i = 0; $i -le 7; $i++) {
  $tag = '{0:D2}' -f $i
  $cmd = "cd /data/local/tmp/MNN && export LD_LIBRARY_PATH=/data/local/tmp/MNN P15_REPEAT=3 MNN_HEX_LAYER_HTP=0:23 P15_NOTUNE=1 && " +
         "./llm_demo model_rd/config_rd_hex_greedy.json /data/local/tmp/g5/prompt_$tag.txt 96 > /data/local/tmp/g5/out_$tag.txt 2>&1"
  & $adb -s $dev shell $cmd 2>&1 | Out-Null
  $n = (& $adb -s $dev shell "grep -c '^\{' /data/local/tmp/g5/out_$tag.txt") -replace '\s', ''
  Write-Host "  prompt_$tag answers=$n"
}

Write-Host "[3/3] pull"
& $adb -s $dev shell 'cd /data/local/tmp/g5 && rm -f /data/local/tmp/g5out.tar && tar cf /data/local/tmp/g5out.tar out_*.txt'
& $adb -s $dev pull /data/local/tmp/g5out.tar "$b\g5out.tar" 2>&1 | Select-Object -Last 1
Push-Location $b; tar xf g5out.tar; Remove-Item g5out.tar -ErrorAction SilentlyContinue; Pop-Location
Write-Host "  out files: $((Get-ChildItem "$b\out_*.txt" -ErrorAction SilentlyContinue).Count)"
Write-Host "G5 BATCH DONE"
