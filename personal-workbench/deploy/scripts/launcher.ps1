param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'status', 'backup')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$AppHome   = Split-Path -Parent $ScriptDir
$Java      = Join-Path $AppHome 'runtime\bin\java.exe'
$Jar       = Join-Path $AppHome 'app\workbench.jar'
$DataDir   = Join-Path $AppHome 'data'
$LogDir    = Join-Path $AppHome 'logs'
$ConfigDir = Join-Path $AppHome 'config'
$BackupDir = Join-Path $DataDir 'backups'
$PidFile   = Join-Path $DataDir 'workbench.pid'
$JarName   = 'workbench.jar'
$MaxBackup = 14

function Write-Step {
    param([string]$Msg)
    Write-Host "  $Msg" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Msg)
    Write-Host "  $Msg" -ForegroundColor Green
}

function Write-Fail {
    param([string]$Msg)
    Write-Host "  $Msg" -ForegroundColor Red
}

function Get-Port {
    $cfg = Join-Path $ConfigDir 'application.yml'
    if (Test-Path $cfg) {
        $m = Select-String -Path $cfg -Pattern '^\s*port:\s*(\d+)' | Select-Object -First 1
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    return 18080
}

function Ensure-Dirs {
    foreach ($d in @($DataDir, $LogDir, (Join-Path $DataDir 'attachments'), $BackupDir)) {
        if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
    }
    # 探针只验证"可写"：写入成功即可。删除单独容忍失败——杀毒软件/安全软件可能
    # 短暂锁定新建文件导致删除被拒，误判"不可写"会挡住正常用户。
    $probe = Join-Path $DataDir ('.write-test-' + [guid]::NewGuid().ToString('N'))
    $writable = $false
    try {
        Set-Content -Path $probe -Value 'ok' -Encoding ASCII
        $writable = $true
    } catch { }
    if (-not $writable) {
        Write-Fail "数据目录不可写：$DataDir"
        Write-Host ''
        Write-Host '  解决办法：把整个文件夹移动到你有读写权限的位置（例如 D 盘根目录），' -ForegroundColor Yellow
        Write-Host '  不要放在 C 盘 Program Files、桌面同步目录或 OneDrive 里。' -ForegroundColor Yellow
        exit 1
    }
    try { Remove-Item $probe -Force -ErrorAction Stop } catch { }
}

function Get-RunningPid {
    $procs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue
    if ($null -eq $procs) { return 0 }
    foreach ($p in @($procs)) {
        if ($p.CommandLine -and $p.CommandLine -like "*$JarName*") { return [int]$p.ProcessId }
    }
    return 0
}

function Test-PortBusy {
    param([int]$Port)
    $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return ($null -ne $conns)
}

function Test-Health {
    param([string]$HealthUrl)
    try {
        $resp = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 3
        return ($resp.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Start-Workbench {
    Write-Host ''
    Write-Host '  个人工作台' -ForegroundColor White
    Write-Host '  ----------' -ForegroundColor DarkGray
    Write-Host ''

    Ensure-Dirs

    if (-not (Test-Path $Java)) {
        Write-Fail "找不到运行时：$Java"
        Write-Fail '请确认部署包完整解压，runtime 目录必须存在。'
        exit 1
    }
    if (-not (Test-Path $Jar)) {
        Write-Fail "找不到程序包：$Jar"
        Write-Fail '请确认部署包完整解压，app 目录必须存在。'
        exit 1
    }

    $existing = Get-RunningPid
    if ($existing -gt 0) {
        Write-Ok "工作台已在运行（进程号 $existing），直接打开浏览器。"
        Start-Process $script:Url
        exit 0
    }

    $port = Get-Port
    if (Test-PortBusy -Port $port) {
        Write-Fail "端口 $port 已被其他程序占用，无法启动。"
        Write-Host ''
        Write-Host '  解决办法：修改 config\application.yml 里的 server.port，' -ForegroundColor Yellow
        Write-Host '  改成 8081 或其他未被占用的端口，保存后重新双击启动。' -ForegroundColor Yellow
        exit 1
    }

    Write-Step '正在启动，首次启动需要准备数据库，请稍候...'

    $stdout = Join-Path $LogDir 'stdout.log'
    $stderr = Join-Path $LogDir 'stderr.log'
    $jvmArgs = @(
        '-Xms128m',
        '-Xmx256m',
        '-XX:MaxMetaspaceSize=128m',
        '-XX:+UseSerialGC',
        '-Dfile.encoding=UTF-8',
        '-Duser.timezone=Asia/Shanghai',
        "-Dworkbench.home=$AppHome",
        "-Dworkbench.data=$DataDir",
        '-jar', $Jar,
        # 应用参数必须在 -jar 之后；命令行参数优先级最高：确保绑定端口与
        # config/application.yml 一致，不受 SERVER_PORT 等机器级环境变量干扰
        "--server.port=$port"
    )

    try {
        $proc = Start-Process -FilePath $Java -ArgumentList $jvmArgs `
            -WorkingDirectory $AppHome -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    } catch {
        Write-Fail "启动失败：$($_.Exception.Message)"
        exit 1
    }

    Set-Content -Path $PidFile -Value $proc.Id -Encoding ASCII

    $health = "$script:Url/actuator/health"
    $ready = $false
    for ($i = 1; $i -le 90; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Health -HealthUrl $health) { $ready = $true; break }
        if ($i % 10 -eq 0) { Write-Step "已等待 $i 秒..." }
    }

    if (-not $ready) {
        Write-Fail '启动超时（90 秒）。请查看 logs 目录下的日志。'
        Write-Host "  日志目录：$LogDir" -ForegroundColor Yellow
        exit 1
    }

    Write-Ok '启动成功！'
    Write-Host ''
    Write-Host "  访问地址：$script:Url" -ForegroundColor White
    Write-Host '  关闭服务请双击「停止工作台.cmd」' -ForegroundColor DarkGray
    Write-Host ''
    Start-Process $script:Url
    exit 0
}

function Stop-Workbench {
    $procId = Get-RunningPid
    if ($procId -eq 0) {
        Write-Ok '工作台当前未运行。'
        if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
        return
    }

    Write-Step "正在停止工作台（进程号 $procId）..."
    try {
        Invoke-WebRequest -Uri "$script:Url/api/v1/system/shutdown" -Method Post -UseBasicParsing -TimeoutSec 5 | Out-Null
    } catch { }

    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if ((Get-RunningPid) -eq 0) { break }
    }

    if ((Get-RunningPid) -gt 0) {
        Write-Step '正在强制结束进程...'
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
    }

    if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
    Write-Ok '已停止。'
}

function Show-Status {
    $procId = Get-RunningPid
    if ($procId -gt 0) {
        Write-Ok "运行中（进程号 $procId），访问地址：$script:Url"
    } else {
        Write-Host '  未运行。双击「启动工作台.cmd」即可启动。' -ForegroundColor Yellow
    }
}

function New-Backup {
    Ensure-Dirs
    $stamp  = Get-Date -Format 'yyyyMMdd-HHmm'
    $target = Join-Path $BackupDir "workbench-$stamp.db"
    $procId = Get-RunningPid

    if ($procId -gt 0) {
        Write-Step '工作台运行中，使用数据库在线备份（一致性快照）...'
        try {
            $body  = @{ targetPath = $target } | ConvertTo-Json
            $resp  = Invoke-RestMethod -Uri "$script:Url/api/v1/system/backup" `
                        -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 60
            Write-Ok "备份完成：$($resp.file)"
        } catch {
            Write-Fail "在线备份失败：$($_.Exception.Message)"
            Write-Fail '为避免 SQLite WAL 快照不一致，已取消备份；不会直接复制数据库文件。'
            return
        }
    } else {
        $db = Join-Path $DataDir 'workbench.db'
        if (-not (Test-Path $db)) {
            Write-Fail '没有找到数据库文件，可能尚未首次启动。'
            return
        }
        Write-Fail '工作台当前未运行。为保证备份一致性，请先启动工作台，再执行备份。'
        return
    }

    $attach = Join-Path $DataDir 'attachments'
    if (Test-Path $attach) {
        $zip = Join-Path $BackupDir "attachments-$stamp.zip"
        $items = Get-ChildItem $attach -Force
        if ($items.Count -gt 0) {
            Compress-Archive -Path (Join-Path $attach '*') -DestinationPath $zip -Force
        }
    }

    $old = Get-ChildItem $BackupDir -Filter 'workbench-*.db' |
           Sort-Object LastWriteTime -Descending | Select-Object -Skip $MaxBackup
    foreach ($f in $old) { Remove-Item $f.FullName -Force }
    if ($old.Count -gt 0) { Write-Step "已清理 $($old.Count) 份旧备份（保留最近 $MaxBackup 份）。" }

    Write-Ok "备份目录：$BackupDir"
}

$script:Port = Get-Port
$script:Url  = "http://localhost:$script:Port"

switch ($Action) {
    'start'  { Start-Workbench }
    'stop'   { Stop-Workbench }
    'status' { Show-Status }
    'backup' { New-Backup }
}
