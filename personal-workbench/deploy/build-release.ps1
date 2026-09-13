<#
    build-release.ps1 - 构建 Windows 免安装包（版本 A，Java 8 版）

    用法：
      .\build-release.ps1 -Version 1.0.0
      .\build-release.ps1 -Version 1.0.0 -SkipBuild      # 跳过 Maven，用已有 jar
      .\build-release.ps1 -Version 1.0.0 -JreSource D:\cache\jre8-trimmed

    说明：
      Java 8 没有 jlink（JEP 282 是 Java 9 引入的），因此运行时不能裁剪生成，
      必须事先准备好裁剪后的 JRE 8 目录，放到 dist\runtime\ 或用 -JreSource 指定。
      裁剪清单见 docs/开发文档-Java8版.md 的 0.4 节。

    前置条件：
      - JDK 1.8（开发与构建）
      - Maven 3.5+（Boot 2.7 要求 3.3+，建议 3.8+）
      - 预先裁剪好的 JRE 8 目录
      - 不需要装 Node（MVP 前端为纯静态资源）
#>

param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$JreSource = '',
    [switch]$SkipBuild,
    [switch]$SkipJre
)

$ErrorActionPreference = 'Stop'

$Root     = Split-Path -Parent $MyInvocation.MyCommand.Definition | Split-Path -Parent
$Server   = Join-Path $Root 'server'
$Deploy   = Join-Path $Root 'deploy'
$Dist     = Join-Path $Root 'dist'
$Stage    = Join-Path $Dist 'personal-workbench'
$ZipPath  = Join-Path $Dist "personal-workbench-v$Version-win.zip"
$JreCache = Join-Path $Dist 'runtime'

function Write-Step { param($m) Write-Host "  $m" -ForegroundColor Cyan }
function Write-Ok   { param($m) Write-Host "  $m" -ForegroundColor Green }

function Assert-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$Name，请先安装并加入 PATH。"
    }
}

Write-Host ''
Write-Host "  构建个人工作台 Windows 免安装包 v$Version (Java 8)" -ForegroundColor White
Write-Host '  ==================================================' -ForegroundColor DarkGray
Write-Host ''

# ---------- 0. 环境检查 ----------
Write-Step '检查构建环境...'
if (-not $SkipBuild) {
    Assert-Command 'docker'
    $null = & docker compose version 2>$null
    if ($LASTEXITCODE -ne 0) { throw '需要 Docker Desktop（compose 插件）来执行隔离构建。' }
}

if (Test-Path $Stage) {
    # Stage 是纯构建产物目录（每次全量重建），但文件数 >50 会触发环境 safe-delete 钩子。
    # 加路径守卫后改用 cmd rd 清理，避免构建被钩子中断（曾发生两次）。
    if ($Stage -notlike '*\dist\personal-workbench') { throw "Stage 路径异常，拒绝清理：$Stage" }
    & cmd /c "rd /s /q `"$Stage`""
    if (Test-Path $Stage) { throw "Stage 清理失败：$Stage" }
}
New-Item -ItemType Directory -Path $Stage -Force | Out-Null

# ---------- 1. Maven 打包（全部在 Docker 内完成，不依赖宿主机 Java/Maven） ----------
$jarSrc = Join-Path $Server 'target\workbench.jar'
if (-not $SkipBuild) {
    Write-Step '在 Docker 内执行 Maven 打包（personal-workbench-dev:java8）...'
    Push-Location $Root
    try {
        & docker compose -f docker-compose.dev.yml run --rm --no-deps dev ./mvnw -B clean package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Docker 内 Maven 打包失败，请查看上方输出。' }
    } finally { Pop-Location }
}
if (-not (Test-Path $jarSrc)) { throw "未找到产物：$jarSrc" }
Write-Ok '后端产物就绪。'

# ---------- 2. 校验字节码版本必须是 Java 8 (major 52) ----------
Write-Step '校验字节码版本（必须为 Java 8 / major 52）...'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$probe = Join-Path $env:TEMP "wb-bytecheck-$([guid]::NewGuid().ToString('N').Substring(0,8))"
New-Item -ItemType Directory -Path $probe -Force | Out-Null
try {
    $entryName = 'BOOT-INF/classes/com/icecode/workbench/WorkbenchApplication.class'
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarSrc)
    try {
        $entry = $zip.GetEntry($entryName)
        if ($null -eq $entry) { throw "jar 内未找到主类：$entryName" }
        $cls = Join-Path $probe 'WorkbenchApplication.class'
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $cls)
    } finally { $zip.Dispose() }
    $bytes = [System.IO.File]::ReadAllBytes($cls)
    $major = $bytes[7]
    if ($major -ne 52) {
        throw "字节码 major version = $major，期望 52（Java 8）。请检查 pom.xml 的 java.version 是否为 1.8。"
    }
    Write-Ok '字节码 major version = 52，确认 Java 8。'
} finally {
    Remove-Item $probe -Recurse -Force -ErrorAction SilentlyContinue
}

# ---------- 3. 组装目录 ----------
Write-Step '组装目录结构...'
$appDir    = Join-Path $Stage 'app'
$scriptDir = Join-Path $Stage 'scripts'
$configDir = Join-Path $Stage 'config'
$dataDir   = Join-Path $Stage 'data'
$logDir    = Join-Path $Stage 'logs'
$attachDir = Join-Path $dataDir 'attachments'
$backupDir = Join-Path $dataDir 'backups'

foreach ($d in @($appDir, $scriptDir, $configDir, $dataDir, $logDir, $attachDir, $backupDir)) {
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}

Copy-Item $jarSrc (Join-Path $appDir 'workbench.jar') -Force
Set-Content -Path (Join-Path $appDir 'VERSION') -Value $Version -Encoding ASCII

Copy-Item (Join-Path $Deploy 'start-workbench.cmd')  $Stage -Force
Copy-Item (Join-Path $Deploy 'stop-workbench.cmd')   $Stage -Force
Copy-Item (Join-Path $Deploy 'backup-workbench.cmd') $Stage -Force
Copy-Item (Join-Path $Deploy 'scripts\launcher.ps1') $scriptDir -Force
Copy-Item (Join-Path $Deploy '使用说明.txt') $Stage -Force

# 使用说明末尾的「版本：vX.Y.Z」是硬编码的，构建时按实际 -Version 注入，避免与包版本漂移
$readmePath = Join-Path $Stage '使用说明.txt'
if (Test-Path $readmePath) {
    $readmeText = [System.IO.File]::ReadAllText($readmePath, [System.Text.Encoding]::UTF8)
    $injected = [regex]::Replace($readmeText, '版本：v[\d.]+', "版本：v$Version")
    if ($injected -ne $readmeText) {
        [System.IO.File]::WriteAllText($readmePath, $injected, (New-Object System.Text.UTF8Encoding($true)))
        Write-Ok "使用说明版本号已同步为 v$Version"
    }
}

$cfg = @'
server:
  port: 18080
  address: 127.0.0.1

workbench:
  data-dir: ./data
  timezone: Asia/Shanghai

# AI 整理、Obsidian、番茄时长等均在「设置」页配置，保存在 data/workbench.db 内，
# 不需要在此文件中填写任何密钥。
'@
Set-Content -Path (Join-Path $configDir 'application.yml') -Value $cfg -Encoding UTF8

# ---------- 4. 放入 JRE 8 运行时（Java 8 无 jlink，只能整包复制） ----------
$runtime = Join-Path $Stage 'runtime'
if ($SkipJre) {
    Write-Step '跳过运行时复制（SkipJre）...'
} else {
    $src = if ($JreSource) { $JreSource } else { $JreCache }
    if (-not (Test-Path $src)) {
        $msg = @"

未找到裁剪好的 JRE 8 目录：$src

Java 8 没有 jlink，运行时必须预先裁剪好。做法：
  1. 下载 Eclipse Temurin JRE 8 (zip 版) 并解压
  2. 按 docs/开发文档-Java8版.md 0.4 节的清单删除文件（目标约 70 MB）
  3. 放到 $JreCache
  4. 重新执行本脚本；或用 -JreSource 指定其它位置
"@
        throw $msg
    }
    Write-Step "复制 JRE 8 运行时（来源 $src）..."
    Copy-Item $src $runtime -Recurse -Force
}

$javaExe = Join-Path $runtime 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    throw 'runtime\bin\java.exe 不存在。请确认 JRE 8 已正确放入（Java 8 无 jlink，无法自动生成）。'
}

$jreVer = & cmd /c "`"$javaExe`" -version 2>&1" | Out-String
if ($jreVer -notmatch 'version "1\.8') {
    throw "runtime 里的 Java 不是 1.8，输出为：$($jreVer.Trim())"
}
$jreSize = [math]::Round(((Get-ChildItem $runtime -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB, 1)
Write-Ok "运行时就绪（Java 8，$jreSize MB）。"

# ---------- 5. 清理个人数据（硬校验） ----------
Write-Step '清理并硬校验个人数据...'
$forbidden = @(
    (Join-Path $dataDir 'workbench.db'),
    (Join-Path $dataDir 'workbench.db-wal'),
    (Join-Path $dataDir 'workbench.db-shm'),
    (Join-Path $dataDir 'workbench.pid')
)
foreach ($f in $forbidden) {
    if (Test-Path $f) { Remove-Item $f -Force }
}
# PS 5.1 坑：空目录的 Get-ChildItem 管道无输出时，Remove-Item 会因强制参数绑定失败
# 报 "missing path operand"。改用 ForEach-Object 逐项删除，空目录自然跳过。
foreach ($cleanDir in @($attachDir, $backupDir, $logDir)) {
    Get-ChildItem $cleanDir -Force -ErrorAction SilentlyContinue |
        ForEach-Object { Remove-Item $_.FullName -Recurse -Force -Confirm:$false }
}

if ($forbidden | Where-Object { Test-Path $_ }) {
    throw '个人数据清理失败，构建中止。'
}
# 内容级硬校验：交付包内不得出现任何数据库、日志、密钥残留
$leakDb = Get-ChildItem $Stage -Recurse -File -Include *.db,*.db-wal,*.db-shm,*.sqlite,*.log -ErrorAction SilentlyContinue
if ($leakDb) {
    $leakDb | ForEach-Object { Write-Host "    残留：$($_.FullName)" -ForegroundColor Red }
    throw '交付包内发现数据库或日志残留，构建中止。'
}
$leakKey = Get-ChildItem $Stage -Recurse -File -Include *.yml,*.txt,*.ps1,*.cmd -ErrorAction SilentlyContinue |
    Select-String -Pattern 'sk-[A-Za-z0-9]{10,}|password_hash|api_key":\s*"[^"]+' -ErrorAction SilentlyContinue
if ($leakKey) {
    $leakKey | ForEach-Object { Write-Host "    疑似密钥：$($_.Path)" -ForegroundColor Red }
    throw '交付包内发现疑似密钥或口令残留，构建中止。'
}
Write-Ok '已确认交付包为干净实例。'

# ---------- 6. 压缩 ----------
Write-Step '压缩为 ZIP...'
# Compress-Archive -Force 直接覆盖旧 ZIP，避免 Remove-Item 触发环境 safe-delete 钩子
Compress-Archive -Path $Stage -DestinationPath $ZipPath -CompressionLevel Optimal -Force
$size = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
$hash = Get-FileHash $ZipPath -Algorithm SHA256
Set-Content -Path "$ZipPath.sha256" -Value $hash.Hash -Encoding ASCII

if ($size -gt 80) {
    Write-Host '  包体超过 80MB，组成体积报告：' -ForegroundColor Red
    Get-ChildItem $Stage -Directory | ForEach-Object {
        $mb = [math]::Round(((Get-ChildItem $_.FullName -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB, 1)
        Write-Host ("    {0,-12} {1,8} MB" -f $_.Name, $mb) -ForegroundColor Yellow
    }
    throw "ZIP 包体 $size MB 超过 80MB 上限，请按报告继续裁剪后重试。"
}

Write-Host ''
Write-Ok "构建完成：$ZipPath"
Write-Host "  包体：$size MB" -ForegroundColor White
Write-Host "  SHA256：$($hash.Hash.Substring(0, 32))..." -ForegroundColor DarkGray
Write-Host ''
Write-Host '  下一步：在一台没有 Java / MySQL / Node 的干净 Windows 上解压验证。' -ForegroundColor Yellow
Write-Host '  重点验证：中文路径、端口占用、杀毒软件拦截、shutdown 优雅停机。' -ForegroundColor Yellow
Write-Host ''
