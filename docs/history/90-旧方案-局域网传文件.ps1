# 局域网传文件
# ------------------------------------------------------------
# 在这台电脑上开一个临时网页服务器，手机连同一个 WiFi 就能打开网页
# 把游戏文件下载过去。文件不出局域网，不经任何第三方服务器。
#
# 用法：双击 局域网传文件.bat
# ------------------------------------------------------------

$ErrorActionPreference = "Continue"

$GAME = "D:\mgq"
$PORT = 8000

function Say($t, $c = "White") { Write-Host $t -ForegroundColor $c }
function Line { Write-Host ("-" * 60) -ForegroundColor DarkGray }

Say ""
Say "============================================================" "Cyan"
Say "   局域网传文件（手机 <- 电脑）" "Cyan"
Say "============================================================" "Cyan"
Say ""

# -- 检查游戏目录 --------------------------------------------
if (-not (Test-Path -LiteralPath $GAME)) {
    Say "[X] 找不到游戏目录：$GAME" "Red"
    Read-Host "按回车退出"; exit 1
}
$size = (Get-ChildItem -LiteralPath $GAME -Recurse -File -ErrorAction SilentlyContinue |
         Measure-Object -Property Length -Sum).Sum
Say ("[OK] 游戏目录: $GAME  (" + [math]::Round($size/1GB,2) + " GB)")

# -- 找 Python ------------------------------------------------
$py = $null
foreach ($c in @("python","py")) {
    $cmd = Get-Command $c -ErrorAction SilentlyContinue
    if ($cmd) { $py = $cmd.Source; break }
}
if (-not $py -and (Test-Path "C:\Program Files\Python312\python.exe")) {
    $py = "C:\Program Files\Python312\python.exe"
}
if (-not $py) {
    Say "[X] 找不到 Python，无法启动服务器。" "Red"
    Say "    请先装 Python：https://www.python.org/downloads/" "Red"
    Read-Host "按回车退出"; exit 1
}
Say "[OK] Python: $py"

# -- 找本机局域网 IP -----------------------------------------
$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
       Where-Object {
           $_.IPAddress -notmatch '^127\.' -and
           $_.IPAddress -notmatch '^169\.254\.' -and
           $_.PrefixOrigin -ne 'WellKnown'
       } |
       Select-Object -ExpandProperty IPAddress

# 关键：不能靠"网段像不像家庭网络"来判断。
# VMware / Hyper-V / WSL 的虚拟网卡同样会用 192.168.x.1，
# 会被误选成"局域网 IP"，导致手机根本连不上。
# 正确做法：取【有默认网关】的那张网卡 —— 那才是真正在联网的物理网卡。
$lan = $null
$routes = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
          Sort-Object RouteMetric
foreach ($r in $routes) {
    $cand = (Get-NetIPAddress -InterfaceIndex $r.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
             Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
             Select-Object -First 1).IPAddress
    if ($cand) { $lan = $cand; $lanIf = $r.InterfaceAlias; break }
}
# 兜底：万一拿不到路由表
if (-not $lan) {
    $lan = $ips | Where-Object { $_ -match '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)' } | Select-Object -First 1
}
if (-not $lan) { $lan = $ips | Select-Object -First 1 }

if (-not $lan) {
    Say "[X] 没有找到局域网 IP，电脑可能没连网络。" "Red"
    Read-Host "按回车退出"; exit 1
}
Say "[OK] 本机局域网 IP: $lan" + $(if ($lanIf) { "  (网卡: $lanIf)" } else { "" })
Say ""
$others = $ips | Where-Object { $_ -ne $lan }
if ($others) {
    Say "  （忽略这些虚拟网卡地址，手机连不上：$($others -join ', ')）" "DarkGray"
    Say ""
}

# -- 放行防火墙（只对专用网络）--------------------------------
Line
Say "[1/2] 配置防火墙" "Yellow"
$ruleName = "mgq-local-transfer-$PORT"
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    Say "  规则已存在，跳过" "DarkGray"
} else {
    try {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $PORT -Profile Private -ErrorAction Stop | Out-Null
        Say "  已放行 TCP $PORT（仅专用网络）" "Green"
    } catch {
        Say "  [!] 防火墙规则添加失败（可能需要管理员权限）" "DarkYellow"
        Say "      如果手机连不上，试着用管理员身份重跑本脚本" "DarkYellow"
    }
}
Say ""

# -- 启动服务器 ----------------------------------------------
Line
Say "[2/2] 启动服务器" "Yellow"
Say ""
Say "  手机连上【和这台电脑同一个 WiFi】，然后用手机浏览器打开：" "White"
Say ""
Say "        http://${lan}:${PORT}/" "Green"
Say ""
Say "  打开后会看到文件列表，点文件即可下载。" "Gray"
Say "  游戏主程序是「勇者大战魔物娘三章完全汉化版.exe」。" "Gray"
Say ""
Say "  ★ 建议：不要一个个点。用手机上的下载器（如 ADM）或" "Yellow"
Say "    文件管理器，直接把这个网址当作目录整体拉取。" "Yellow"
Say "    更省事的办法是用 SMB（见下）。" "Yellow"
Say ""
Line
Say "  按 Ctrl+C 可以停止服务器。" "DarkGray"
Say ""
Line

# 附加提示：SMB 方式
Say ""
Say "  ---------- 更推荐：SMB 共享 ----------" "Cyan"
Say "  用手机文件管理器整个文件夹拖过去，比网页点文件靠谱得多。" "Gray"
Say "  在【管理员】PowerShell 里跑一次：" "Gray"
Say ""
Say "      New-SmbShare -Name mgq -Path D:\mgq -ReadAccess Everyone" "White"
Say ""
Say "  然后手机文件管理器（Solid Explorer / MiXplorer / Material Files）" "Gray"
Say "  新建 SMB 连接，地址填：  smb://${lan}/mgq" "Gray"
Say "  用户名密码填这台电脑的 Windows 账户。" "Gray"
Say "  拷完记得删掉共享：" "Gray"
Say "      Remove-SmbShare -Name mgq -Force" "White"
Say ""
Line
Say ""

# -- 真正启动 ------------------------------------------------
try {
    & $py -m http.server $PORT --directory "$GAME" --bind 0.0.0.0
} catch {
    Say "服务器异常退出: $($_.Exception.Message)" "Red"
}
Read-Host "按回车退出"
