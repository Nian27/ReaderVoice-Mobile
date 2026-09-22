# run_m3_step1_refresh.ps1 — 规则层改动后：重生成语料 → 桌面 dump → 设备 G2 → 设备 M0 批跑
$ErrorActionPreference = 'Continue'
$root = 'E:\AndroidStudioProjects\ReaderVoiceMobile'
$m0 = "$root\runs\mobile_005_director_real\m0_protocol_probe"
$m2 = "$root\runs\mobile_005_director_real\m2_semantic_parity"

Write-Host "=== [1] 重生成 M0 fixture（含新的 cue 切分/实体前缀消歧）==="
& "$root\gradlew.bat" -p $root :data-room:test --tests 'com.readervoice.data.m0.M0SelectionFixtureTest' --offline --rerun-tasks 2>&1 |
  Select-String -Pattern '\[M0\]|BUILD (SUCCESSFUL|FAILED)' | Select-Object -Last 5
Get-Content "$m0\stats.txt" | Select-String -Pattern 'rule_status|rule_speaker_null|recall|surfaces'

Write-Host "`n=== [2] 桌面 M2 dump（新 trace + canonical）==="
& "$root\gradlew.bat" -p $root :data-room:test --tests 'com.readervoice.data.m2.M2SemanticParityTest' --offline --rerun-tasks 2>&1 |
  Select-String -Pattern '\[M2\]|BUILD (SUCCESSFUL|FAILED)' | Select-Object -Last 3

Write-Host "`n=== [3] 设备 G2（与当前代码对齐）==="
& "$root\tools\mobile005\run_m2_device.ps1" 2>&1 | Select-String -Pattern 'G2|desktop lines|device  lines|FAIL|md5'

Write-Host "`n=== [4] 设备 M0 批跑（25 目标 x 3 次）==="
& "$root\tools\mobile005\run_m0_v2.ps1" 2>&1 | Select-String -Pattern 'P1|P2|P3|P4|P6|Agreement|MANUAL_GOLD|一致|越界|answers=3' | Select-Object -Last 14

Write-Host "`n=== ALL DONE ==="
