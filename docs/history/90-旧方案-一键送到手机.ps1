# 一键把游戏送到手机
# ------------------------------------------------------------
#  1. 环境自检（adb / APK / 游戏目录）
#  2. 等手机连上 USB 调试
#  3. 检查手机剩余空间
#  4. 安装 Winlator
#  5. 推送游戏文件
#  6. 核对结果
#
# 注意：本脚本故意使用 $ErrorActionPreference = "Continue"。
# 因为 adb 会把正常提示（如 "* daemon not running"）写到 stderr，
# 若设为 Stop，PowerShell 会把它当成致命错误而中止脚本。
# ------------------------------------------------------------

$ErrorActionPreference = "Continue"

# -- 配置 ----------------------------------------------------
$ROOT        = Split-Path -Parent $MyInvocation.MyCommand.Path
$ADB         = Join-Path $ROOT "platform-tools\adb.exe"
$GAME_SRC    = "D:\mgq"                     # 电脑上的游戏目录
$DEST_PARENT = "/sdcard/Download"           # 手机上的父目录
$DEST        = "$DEST_PARENT/mgq"           # 游戏最终落点（必须纯英文）

function Say($t, $c = "White") { Write-Host $t -ForegroundColor $c }
function Line { Write-Host ("-" * 62) -ForegroundColor DarkGray }

# 调用 adb，屏蔽 stderr 噪音，返回输出文本
function Adb {
    param([string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $o = & $ADB @Arguments 2>&1 | Where-Object { $_ -notmatch "daemon (not running|started successfully)" }
        return ($o | Out-String)
    } finally { $ErrorActionPreference = $prev }
}

Say ""
Say "============================================================" "Cyan"
Say "   一键把游戏送到手机" "Cyan"
Say "============================================================" "Cyan"
Say ""

# -- 0. 环境自检 ---------------------------------------------
Line
Say "[0/6] 环境自检" "Yellow"

if (-not (Test-Path -LiteralPath $ADB)) {
    Say "  [X] 找不到 adb：$ADB" "Red"
    Say "      请确认 platform-tools 文件夹与脚本在同一目录。" "Red"
    Read-Host "按回车退出"; exit 1
}
Say "  [OK] adb 就绪"

$apk = Get-ChildItem -LiteralPath $ROOT -Filter "Winlator_*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) {
    Say "  [X] 找不到 Winlator APK（Winlator_*.apk）" "Red"
    Read-Host "按回车退出"; exit 1
}
Say ("  [OK] Winlator APK: " + $apk.Name + " (" + [math]::Round($apk.Length/1MB,1) + " MB)")

if (-not (Test-Path -LiteralPath $GAME_SRC)) {
    Say "  [X] 找不到游戏目录：$GAME_SRC" "Red"
    Say "      请先确认 D:\mgq 存在（它是原游戏目录的联接）。" "Red"
    Read-Host "按回车退出"; exit 1
}
$srcSize = (Get-ChildItem -LiteralPath $GAME_SRC -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
Say ("  [OK] 游戏目录: $GAME_SRC  (" + [math]::Round($srcSize/1GB,2) + " GB)")
Say ""

# -- 1. 等手机连接 -------------------------------------------
Line
Say "[1/6] 等待手机连接" "Yellow"
Say ""
Say "  请现在做："
Say "    1. 用数据线把手机连到电脑" "Gray"
Say "    2. 手机上若弹出「允许 USB 调试吗？」-> 勾选「始终允许」-> 确定" "Gray"
Say "    3. 若没弹出：设置 -> 关于手机 -> 连点「版本号」7 次" "Gray"
Say "       然后 设置 -> 系统 -> 开发者选项 -> 打开「USB 调试」" "Gray"
Say ""

Adb @("start-server") | Out-Null
$serial = $null
for ($i = 1; $i -le 90; $i++) {
    $out  = Adb @("devices")
    $line = ($out -split "`r?`n") | Where-Object { $_ -match "^\S+\s+(device|unauthorized|offline)\s*$" } | Select-Object -First 1
    if ($line) {
        $parts = ($line -split "\s+") | Where-Object { $_ }
        $state = $parts[1]
        if ($state -eq "device") { $serial = $parts[0]; break }
        elseif ($state -eq "unauthorized" -and ($i % 10 -eq 1)) {
            Say "  [!] 手机上还没点「允许」——请解锁手机看看有没有弹窗" "DarkYellow"
        }
        elseif ($state -eq "offline" -and ($i % 10 -eq 1)) {
            Say "  [!] 设备离线，试着重新插拔数据线" "DarkYellow"
        }
    }
    if ($i % 6 -eq 1) { Write-Host "      等待中... ($i/90)" -ForegroundColor DarkGray }
    Start-Sleep -Seconds 2
}

if (-not $serial) {
    Say ""
    Say "  [X] 等了 3 分钟仍未等到设备。常见原因：" "Red"
    Say "      - 数据线只能充电不能传数据 -> 换一根线" "Red"
    Say "      - 手机上没点「允许 USB 调试」" "Red"
    Say "      - 开发者选项里的 USB 调试没打开" "Red"
    Read-Host "按回车退出"; exit 1
}

$model = (Adb @("-s",$serial,"shell","getprop","ro.product.model")).Trim()
$sdk   = ((Adb @("-s",$serial,"shell","getprop","ro.build.version.sdk")).Trim() -replace '\D','')
$abi   = (Adb @("-s",$serial,"shell","getprop","ro.product.cpu.abi")).Trim()
Say ""
Say "  [OK] 已连接: $model   (Android SDK $sdk / $abi)" "Green"
if ($sdk -and [int]$sdk -lt 26) { Say "  [!] 系统低于 Android 8.0，Winlator 可能跑不起来" "DarkYellow" }
if ($abi -and $abi -notmatch "arm64") { Say "  [!] 处理器不是 ARM64，Winlator 基本无法运行" "DarkYellow" }
Say ""

# -- 2. 检查空间 ---------------------------------------------
Line
Say "[2/6] 检查手机剩余空间" "Yellow"
$needMB = [math]::Ceiling(($srcSize / 1MB) + 2048)
$df = (Adb @("-s",$serial,"shell","df -m /sdcard")).Trim()
$dfLine = ($df -split "`r?`n") | Where-Object { $_ -match "/sdcard|/storage|Filesystem" } | Select-Object -Last 1
$cols = @()
if ($dfLine) { $cols = ($dfLine -split "\s+") | Where-Object { $_ -match '^\d+$' } }
if ($cols.Count -ge 3) {
    $freeMB = [int]$cols[2]
    Say ("  手机剩余: " + [math]::Round($freeMB/1024,2) + " GB")
    Say ("  本次需要: " + [math]::Round($needMB/1024,2) + " GB")
    if ($freeMB -lt $needMB) {
        Say ""
        Say ("  [X] 空间不够，还差 " + [math]::Round(($needMB-$freeMB)/1024,2) + " GB") "Red"
        Say "      请先在手机上腾出空间再跑本脚本。" "Red"
        Read-Host "按回车退出"; exit 1
    }
    Say "  [OK] 空间充足" "Green"
} else {
    Say "  (拿不到手机空间信息，跳过检查)" "DarkGray"
}
Say ""

# -- 3. 安装 Winlator ----------------------------------------
Line
Say "[3/6] 安装 Winlator" "Yellow"
$pkgs = Adb @("-s",$serial,"shell","pm","list","packages")
if ($pkgs -match "com\.winlator") {
    Say "  已经装过 Winlator，跳过（要重装请先在手机上卸载）" "DarkGray"
} else {
    Say "  正在安装，手机上若弹出确认框请点允许..."
    $r = Adb @("-s",$serial,"install","-r",$apk.FullName)
    if ($r -match "Success") { Say "  [OK] 安装成功" "Green" }
    else {
        Say "  [X] 安装失败：" "Red"
        ($r -split "`r?`n") | Where-Object { $_.Trim() } | ForEach-Object { Say "      $_" "Red" }
        Say "      可以改成在手机上手动点击 APK 安装一次。" "DarkYellow"
        Read-Host "按回车退出"; exit 1
    }
}
Say ""

# -- 4. 推送游戏 ---------------------------------------------
Line
Say "[4/6] 推送游戏文件（约 4 GB，请耐心等待）" "Yellow"
Say "  过程中不要拔数据线，不要锁屏。" "DarkYellow"
Say ""
# 推送语义要点：adb push <本地目录> <远端已存在的目录>
# 会在远端目录下创建同名子目录。
# 所以这里推到 Download，结果是 /sdcard/Download/mgq/
# 而不是 /sdcard/Download/mgq/mgq/（多套一层）。
Adb @("-s",$serial,"shell","mkdir -p $DEST_PARENT") | Out-Null
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$null = & $ADB -s $serial push "$GAME_SRC" "$DEST_PARENT" 2>&1
$code = $LASTEXITCODE
$sw.Stop()
Say ""
if ($code -ne 0) {
    Say "  [X] 推送失败（adb 返回 $code）" "Red"
    Read-Host "按回车退出"; exit 1
}
Say ("  [OK] 推送完成，用时 " + [math]::Round($sw.Elapsed.TotalMinutes,1) + " 分钟") "Green"
Say ""

# -- 5. 核对 -------------------------------------------------
Line
Say "[5/6] 核对结果" "Yellow"
$remote = (Adb @("-s",$serial,"shell","du -sk $DEST")).Trim()
$rKB = $null
if ($remote -match '(\d+)') { $rKB = [double]$Matches[1] }
if ($rKB) {
    $rMB = [math]::Round($rKB/1024, 1)
    $lMB = [math]::Round($srcSize/1MB, 1)
    Say ("  电脑: $lMB MB")
    Say ("  手机: $rMB MB")
    if ([math]::Abs($rMB - $lMB) -lt 50) { Say "  [OK] 大小一致" "Green" }
    else { Say "  [!] 大小对不上，可能没传完，建议重跑一次" "DarkYellow" }
} else { Say "  (拿不到手机端大小)" "DarkGray" }

$keyFiles = @("nscript.dat","arc.nsa","arc1.nsa","arc2.nsa","nslua.dll","envdata")
$missing = @()
foreach ($f in $keyFiles) {
    $t = Adb @("-s",$serial,"shell","ls `"$DEST/$f`"")
    if ($t -notmatch [regex]::Escape($f)) { $missing += $f }
}
if ($missing.Count -eq 0) { Say "  [OK] 关键文件齐全" "Green" }
else { Say ("  [!] 缺少: " + ($missing -join ", ")) "DarkYellow" }
Say ""

# -- 6. 收尾 -------------------------------------------------
Line
Say "[6/6] 完成！接下来在手机上做（约 2 分钟）" "Cyan"
Say ""
Say "  1. 打开 Winlator，三个权限全部「允许」"
Say "     首次会显示 Installing system files，等 30~90 秒，不要关" "Gray"
Say ""
Say "  2. 右上角 + 新建容器，两个关键项："
Say "       DX Wrapper      ->  CNC DDraw    <-- 选错会黑屏" "Yellow"
Say "       Windows Version ->  Windows XP" "Yellow"
Say "     其余：Screen Size = 800x600，Box86 Preset = Compatibility" "Gray"
Say ""
Say "  3. 容器设置 -> Drives -> 添加 D: 映射到 /sdcard/Download"
Say ""
Say "  4. 进容器，打开 D:\mgq\，双击游戏 exe"
Say "     (第一次启动会黑屏 10~30 秒，Wine 在初始化)" "Gray"
Say ""
Say "  5. 做桌面图标"
Say "     双指同时点一下 exe -> Create Shortcut" "Gray"
Say "     回 Winlator 主界面 -> 快捷方式旁三个点 -> Add to Home Screen" "Gray"
Say ""
Say "  做完第 5 步，安卓桌面上就有图标，点一下直接进游戏。" "Green"
Say ""
Say "  详细说明：D:\手机安装步骤.txt" "DarkGray"
Say ""
Line
Read-Host "按回车退出"
