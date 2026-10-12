# 把 device-models\ocr 推到设备系统目录 /system/etc/whisper/models（需要 userdebug + adb root）。
# 语音识别与声纹已改云端（Step 流式 + 讯飞声纹），设备上只剩 OCR 模型需要推送。
# device-models 里的 ASR/VAD/声纹/标点模型不再使用，保留在磁盘仅作备份；
# 本地流式字幕模型实测会把整机拖挂（app 以 system uid 运行），严禁推送 streaming/。
# 用法：powershell -File scripts\push-models.ps1 [-Serial <序列号>]
param(
    [string]$Serial = ""
)
# adb 会把正常提示写到 stderr，不能用 ErrorActionPreference=Stop，改为检查退出码。
$ErrorActionPreference = "Continue"

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$adbArgs = @()
if ($Serial) { $adbArgs = @("-s", $Serial) }

$src = Join-Path $PSScriptRoot "..\device-models\ocr"
$dst = "/system/etc/whisper/models"

function Adb { & $adb @adbArgs @args; if ($LASTEXITCODE -ne 0) { throw "adb $args failed" } }

Adb root | Out-Null
Start-Sleep -Seconds 2
Adb wait-for-device
cmd /c "`"$adb`" $($adbArgs -join ' ') remount 2>&1"
if ($LASTEXITCODE -ne 0) { throw "adb remount failed" }

Adb shell "rm -rf $dst && mkdir -p $dst"
Get-ChildItem $src -Force | ForEach-Object { Adb push $_.FullName "$dst/" }
Adb shell "chown -R root:root /system/etc/whisper && find /system/etc/whisper -type d -exec chmod 755 {} + && find /system/etc/whisper -type f -exec chmod 644 {} + && restorecon -R /system/etc/whisper"
Adb shell "ls -lRZ /system/etc/whisper"
