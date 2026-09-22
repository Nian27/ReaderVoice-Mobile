<#
.SYNOPSIS
Checks whether every arm64 native library has 16 KB-aligned ELF PT_LOAD segments.

.DESCRIPTION
Android ZIP alignment cannot repair an ELF with 4 KB PT_LOAD alignment. This
script checks the unpackaged shared objects with the installed Android NDK and,
when supplied, also checks the APK package alignment required for 16 KB devices.

.EXAMPLE
.\tools\check_elf_16k.ps1 -LibraryDirectory .\app-android\src\main\jniLibs\arm64-v8a -ApkPath .\app-android\build\outputs\apk\debug\app-android-debug.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path $_ -PathType Container })]
    [string]$LibraryDirectory,

    [string]$ApkPath,

    [string]$NdkRoot = 'C:\Users\Administrator\AppData\Local\Android\Sdk\ndk\27.0.12077973'
)

$ErrorActionPreference = 'Stop'
$readElf = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path $readElf)) {
    throw "llvm-readelf not found: $readElf"
}

$libraries = Get-ChildItem -LiteralPath $LibraryDirectory -Filter '*.so' -File | Sort-Object Name
if ($libraries.Count -eq 0) {
    throw "No .so files found in $LibraryDirectory"
}

$failed = [System.Collections.Generic.List[string]]::new()
foreach ($library in $libraries) {
    $loadLines = & $readElf -lW $library.FullName | Select-String '^\s*LOAD\s+'
    $alignments = @($loadLines | ForEach-Object {
        $tokens = $_.Line -split '\s+'
        $tokens[-1]
    })
    $is16k = $alignments.Count -gt 0 -and ($alignments | Where-Object { $_ -ne '0x4000' }).Count -eq 0
    $status = if ($is16k) { 'PASS' } else { 'FAIL' }
    "{0,-4} {1,-34} LOAD align: {2}" -f $status, $library.Name, ($alignments -join ', ')
    if (-not $is16k) { $failed.Add($library.Name) }
}

if ($ApkPath) {
    if (-not (Test-Path $ApkPath -PathType Leaf)) { throw "APK not found: $ApkPath" }
    $buildTools = Get-ChildItem 'C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools' -Directory |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Android build-tools not found' }
    $zipAlign = Join-Path $buildTools.FullName 'zipalign.exe'
    if (-not (Test-Path $zipAlign)) { throw "zipalign not found: $zipAlign" }
    & $zipAlign -v -c -P 16 4 $ApkPath
    if ($LASTEXITCODE -ne 0) { $failed.Add('APK zip alignment') }
}

if ($failed.Count -gt 0) {
    throw "16 KB Native Artifact Gate FAILED: $($failed -join ', ')"
}
"16 KB Native Artifact Gate PASS: $($libraries.Count) native libraries checked."
