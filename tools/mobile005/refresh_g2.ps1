# refresh_g2.ps1 — 语义层改动后重新对齐 G2（桌面 dump → 设备 dump → 判分）
$ErrorActionPreference = 'Continue'
$root = 'E:\AndroidStudioProjects\ReaderVoiceMobile'
Write-Host "=== [1] 桌面 M2 dump ==="
& "$root\gradlew.bat" -p $root :data-room:test --tests 'com.readervoice.data.m2.M2SemanticParityTest' --offline --rerun-tasks 2>&1 |
  Select-String -Pattern '\[M2\]|BUILD (SUCCESSFUL|FAILED)' | Select-Object -Last 2
Write-Host "=== [2] 设备 G2 ==="
& "$root\tools\mobile005\run_m2_device.ps1" 2>&1 | Select-String -Pattern 'desktop lines|device  lines|G2 |FAIL'
Write-Host "=== [3] 回放自校验 ==="
& "$root\gradlew.bat" -p $root :semantic-core:test --offline --rerun-tasks 2>&1 |
  Select-String -Pattern 'BUILD (SUCCESSFUL|FAILED)' | Select-Object -Last 1
Write-Host "=== G2 REFRESH DONE ==="
