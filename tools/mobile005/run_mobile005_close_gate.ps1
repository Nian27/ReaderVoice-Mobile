# run_mobile005_close_gate.ps1 — MOBILE-005 Close Gate（前台服务宿主版）
# 3 次完整整章 + 1 次主动 epoch cancel；运行由 ChapterDirectorService 承载，App 可切后台。
param(
  [string]$Dev = '10.40.137.201:32927',
  [int]$FullRuns = 3,
  [string]$Book = 'book-4455b46ef2d2',
  [int]$From = 60,
  [int]$To = 200,
  [int]$MaxCalls = 60
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$out = 'E:\AndroidStudioProjects\ReaderVoiceMobile\runs\mobile_005_director_real\close_gate'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$pkg = 'com.readervoice.app'

function Sh([string]$cmd) { & $adb -s $Dev shell $cmd 2>&1 }
function Lines() { (Sh "run-as $pkg cat files/chapter_director/script_lines.jsonl 2>/dev/null | wc -l") -replace '\s','' }

function Pull-Run([string]$dir) {
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Sh "run-as $pkg cat files/chapter_director/stats.txt > /data/local/tmp/cg_stats.json 2>/dev/null; run-as $pkg cat files/chapter_director/script_lines.jsonl > /data/local/tmp/cg_lines.jsonl 2>/dev/null" | Out-Null
  & $adb -s $Dev pull /data/local/tmp/cg_stats.json "$dir\stats.json" 2>&1 | Out-Null
  & $adb -s $Dev pull /data/local/tmp/cg_lines.jsonl "$dir\script_lines.jsonl" 2>&1 | Out-Null
  Sh "logcat -d -s QwenEngine" | Select-String -Pattern 'metrics backend|generate_deadline done' |
    ForEach-Object { $_.Line } | Set-Content -Encoding utf8 "$dir\qwen_metrics.log"
}

function Wait-Stats([int]$timeoutSec) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
    Start-Sleep -Seconds 10
    $ok = (Sh "run-as $pkg test -s files/chapter_director/stats.txt && echo yes || echo no") -replace '\s',''
    if ($ok -eq 'yes') { return $true }
  }
  return $false
}

function Launch() {
  # 无线端点（含冒号）才需要重连；USB 串号跳过
  if ($Dev -match ':') { & $adb -s $Dev connect $Dev 2>&1 | Out-Null }
  Sh "svc power stayon true; am force-stop $pkg; run-as $pkg rm -rf files/chapter_director" | Out-Null
  Sh "logcat -c" | Out-Null
  Sh "am start -W -n $pkg/.ChapterDirectorActivity --es book $Book --ei from $From --ei to $To --ei maxcalls $MaxCalls --es backend hexagon --ez autorun true" |
    Select-String -Pattern 'TotalTime|Error' | ForEach-Object { $_.Line }
  # ★ 主动把 App 切后台：证明前台服务承载让采集不受「切 App」影响（旧实现会冻结）
  Start-Sleep -Seconds 12
  Sh "input keyevent KEYCODE_HOME" | Out-Null
}

& $adb -s $Dev shell "pm grant $pkg android.permission.POST_NOTIFICATIONS" 2>&1 | Out-Null

for ($i = 1; $i -le $FullRuns; $i++) {
  Write-Host "=== FULL RUN $i / $FullRuns ==="
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  Launch
  $ok = Wait-Stats -timeoutSec 900
  Write-Host ("  stats=" + $ok + " lines=" + (Lines) + " elapsed=" + [math]::Round($sw.Elapsed.TotalSeconds,0) + "s")
  if ($ok) { Pull-Run "$out\run_$i" }
}

Write-Host "=== CANCEL RUN ==="
Launch
Start-Sleep -Seconds 75
Sh "am start -n $pkg/.ChapterDirectorActivity" | Out-Null      # 拉回前台以便点击协作式取消
Start-Sleep -Seconds 4
$xml = Sh "uiautomator dump /sdcard/cg.xml >/dev/null 2>&1; cat /sdcard/cg.xml" | Out-String
$m = [regex]::Match($xml, 'text="取消（epoch\+\+）"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($m.Success) {
  $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
  $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
  Write-Host "  tap cancel at ($cx,$cy)"
  Sh "input tap $cx $cy" | Out-Null
} else {
  Write-Host "  ★ 未找到取消按钮（Compose 语义树），取消步骤记为未验证"
}
$ok = Wait-Stats -timeoutSec 420
Write-Host ("  stats=" + $ok + " lines=" + (Lines))
if ($ok) { Pull-Run "$out\run_cancel" }

Write-Host "CLOSE GATE RUNS DONE"
