# run_m2_device.ps1 — 重建 APK → 安装 → 推送新 trace → 设备端 G2 dump → 拉回
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = 'A3TE025B03003242'
$root = 'E:\AndroidStudioProjects\ReaderVoiceMobile'
$m2 = "$root\runs\mobile_005_director_real\m2_semantic_parity"

Write-Host "[1/6] build app"
& "$root\gradlew.bat" -p $root :app-android:assembleDebug --offline 2>&1 | Select-String -Pattern 'BUILD (SUCCESSFUL|FAILED)' | Select-Object -Last 1

Write-Host "[2/6] install"
& $adb -s $dev shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
& $adb -s $dev install -r "$root\app-android\build\outputs\apk\debug\app-android-debug.apk" 2>&1 | Select-Object -Last 1

Write-Host "[3/6] push trace + input"
& $adb -s $dev push "$m2\trace.json" /data/local/tmp/m2_trace.json 2>&1 | Select-Object -Last 1
& $adb -s $dev push "$m2\input.txt" /data/local/tmp/m2_input.txt 2>&1 | Select-Object -Last 1
& $adb -s $dev shell 'run-as com.readervoice.app mkdir -p files/m2; run-as com.readervoice.app cp /data/local/tmp/m2_trace.json files/m2/trace.json; run-as com.readervoice.app cp /data/local/tmp/m2_input.txt files/m2/input.txt; run-as com.readervoice.app rm -rf files/m2_parity'

Write-Host "[4/6] run device semantic parity"
& $adb -s $dev shell 'am force-stop com.readervoice.app'
& $adb -s $dev shell 'am start -n com.readervoice.app/.SemanticParityActivity --es infile m2/input.txt --es trace m2/trace.json --es tag device' 2>&1 | Select-Object -Last 1
# 等待：文件非空且大小连续两次一致（避免"刚创建就拉"或"还在写就拉"）
$prev = -1; $stable = 0; $waited = 0
for ($i = 0; $i -lt 120; $i++) {
  Start-Sleep -Seconds 5; $waited += 5
  $sz = (& $adb -s $dev shell 'run-as com.readervoice.app stat -c %s files/m2_parity/device_canonical.txt 2>/dev/null || echo 0') -replace '\s', ''
  if ($sz -match '^\d+$' -and [int]$sz -gt 0) {
    if ([int]$sz -eq $prev) { $stable++ } else { $stable = 0 }
    $prev = [int]$sz
    if ($stable -ge 1) { Write-Host "  done after ${waited}s (size=$sz)"; break }
  }
}
if ($stable -lt 1) {
  Write-Host "  ★ TIMEOUT after ${waited}s —— 读取 Activity UI 文本以自证原因："
  & $adb -s $dev shell 'uiautomator dump /sdcard/u_fail.xml >/dev/null 2>&1; cat /sdcard/u_fail.xml' 2>&1 |
    Select-String -Pattern 'text="[^"]*(FAILED|targets=|paragraphs=)[^"]*"' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { "    " + $_.Value }
}

Write-Host "[5/6] pull"
& $adb -s $dev shell 'run-as com.readervoice.app cat files/m2_parity/device_canonical.txt > /data/local/tmp/dc.txt; run-as com.readervoice.app cat files/m2_parity/device.jsonl > /data/local/tmp/dj.txt'
& $adb -s $dev pull /data/local/tmp/dc.txt "$m2\device_canonical.txt" 2>&1 | Select-Object -Last 1
& $adb -s $dev pull /data/local/tmp/dj.txt "$m2\device_semantic.jsonl" 2>&1 | Select-Object -Last 1

Write-Host "[6/6] judge"
node "$root\tools\mobile005\semantic_parity.js" "$m2\desktop_canonical.txt" "$m2\device_canonical.txt"
