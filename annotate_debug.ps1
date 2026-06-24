# annotate_debug.ps1 — Annotate 功能调试日志抓取
# 用法：.\annotate_debug.ps1
$ErrorActionPreference = "SilentlyContinue"

Write-Host ""
Write-Host "=== Annotate 调试日志抓取 ===" -ForegroundColor Cyan
Write-Host ""

# Step 1: 清除 logcat 缓冲
Write-Host "[1/3] 清除 logcat..." -ForegroundColor Yellow
adb logcat -c
Start-Sleep -Milliseconds 500
Write-Host "  OK" -ForegroundColor Green

# Step 2: 等待用户操作
Write-Host ""
Write-Host "[2/3] 请在手机/模拟器上操作：" -ForegroundColor Yellow
Write-Host "  1. 打开 OC Remote app" -ForegroundColor White
Write-Host "  2. 打开一个代码文件（从工具卡片或文件树）" -ForegroundColor White
Write-Host "  3. 长按选中一段文字（弹出选区菜单）" -ForegroundColor White
Write-Host "  4. 观察菜单里有没有 Annotate" -ForegroundColor White
Write-Host "  5. 点击 Annotate（没反应也点）" -ForegroundColor White
Write-Host "  6. 如果没有 Annotate，点一下 Copy 做对比" -ForegroundColor White
Write-Host ""
Write-Host "  ※ 操作时观察菜单长什么样（可截图）" -ForegroundColor DarkGray
Write-Host ""
Write-Host "操作完成后按回车继续..." -ForegroundColor Yellow
Read-Host

# Step 3: 导出日志
Write-Host ""
Write-Host "[3/3] 导出日志到 Download 目录..." -ForegroundColor Yellow

# 抓 CodeWebView tag + 可能相关的 chromium webview 日志
adb shell "logcat -d -s CodeWebView:D WebActionModeImpl:D AwContents:D chromium:D > /sdcard/Download/annotate_debug.log"
Start-Sleep -Milliseconds 500

# 同步拉到本地
$localCopy = "$PSScriptRoot\annotate_debug.log"
adb pull /sdcard/Download/annotate_debug.log $localCopy 2>$null

Write-Host "  OK" -ForegroundColor Green
Write-Host ""
Write-Host "=== 日志内容 ===" -ForegroundColor Cyan
Write-Host ""

if ((Test-Path $localCopy) -and ((Get-Item $localCopy).Length -gt 0)) {
    Get-Content $localCopy
} else {
    $remote = adb shell "cat /sdcard/Download/annotate_debug.log"
    if ($remote) { $remote } else { Write-Host "(无日志输出 — startActionMode 可能未被调用)" -ForegroundColor Red }
}

Write-Host ""
Write-Host "=== END ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "文件位置：" -ForegroundColor Green
Write-Host "  手机: /sdcard/Download/annotate_debug.log"
Write-Host "  本地: $localCopy"
Write-Host ""
Write-Host "请将上面的【日志内容】复制粘贴给我" -ForegroundColor Cyan
Write-Host ""
