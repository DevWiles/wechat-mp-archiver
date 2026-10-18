# 一键运行：滚到底 → 扫链接 → 抓正文（由 run.bat 调用，也可在 PowerShell 里直接运行）
param(
    [string]$Proc = ""   # 仅测试用：覆盖要识别的微信进程名
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Set-Location $PSScriptRoot
$Host.UI.RawUI.WindowTitle = "公众号文章归档"

function Ask([string]$prompt, [string]$default) {
    $v = Read-Host "$prompt [回车默认 $default]"
    if ([string]::IsNullOrWhiteSpace($v)) { return $default }
    return $v.Trim()
}

function Yes([string]$prompt) {
    $v = Read-Host "$prompt [Y/n，回车=Y]"
    return -not ($v -match '^\s*[Nn]')
}

# 调 jar：输出直接显示在窗口里，返回退出码
function Jar([string[]]$cmd) {
    if ($Proc) { $cmd += @('--proc', $Proc) }
    & java -jar "target\wx-archiver.jar" @cmd | Out-Host
    return $LASTEXITCODE
}

function Done([int]$code = 0) {
    Write-Host ""
    Read-Host "按回车关闭" | Out-Null
    exit $code
}

function HasUrls([string]$f) { return (Test-Path $f) -and ((Get-Item $f).Length -gt 0) }

# ---------- 环境检查 ----------
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "[错误] 没找到 java，请先安装 JDK 17+"
    Done 1
}
if (-not (Test-Path "target\wx-archiver.jar")) {
    Write-Host "首次运行，正在编译..."
    & mvn -q -B package | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Host "[错误] 编译失败"; Done 1 }
}

# ---------- 参数（直接回车 = 默认值） ----------
$name  = Ask "公众号名称" "韩数同学"
$biz   = Ask "公众号 biz" "Mzg3ODIwNDg5OA=="
$out   = Ask "输出目录" (Join-Path $PSScriptRoot "out\$name")
$delay = Ask "每篇间隔秒数" "3"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$urls = Join-Path $out "urls.txt"

# ---------- 第 1 步：拿链接 ----------
Write-Host ""
Write-Host "===== 第 1 步：获取文章链接 ====="
$reuse = (HasUrls $urls) -and (Yes "已有链接清单 $urls，直接复用？")
if (-not $reuse) {
    Write-Host "请先在微信电脑版里打开「$name」的主页。"
    $auto = Yes "自动慢速滚动到底？（n = 我自己手动滚）"
    while ($auto) {
        Read-Host "回车后，切到微信公众号主页，把鼠标放在文章列表上别动，静止 3 秒自动开始" | Out-Null
        if ((Jar @('scroll', '--biz', $biz, '--out', $urls)) -eq 0) { break }
        $ans = Read-Host "滚动没有完成。R=重试 / M=改为手动滚 / Q=退出 [回车=R]"
        if ($ans -match '^\s*[Qq]') { Done 1 }
        if ($ans -match '^\s*[Mm]') { $auto = $false }
    }
    if (-not $auto) {
        Read-Host "请手动把历史文章一直滚到底（直到不再加载新文章），然后按回车" | Out-Null
        Jar @('scan', '--biz', $biz, '--out', $urls) | Out-Null
    }
    if (-not (HasUrls $urls)) {
        Write-Host "[提示] 没扫到任何链接：确认主页已滚到底、biz 是否正确"
        Done 1
    }
}

# ---------- 第 2 步：抓正文 ----------
Write-Host ""
Write-Host "===== 第 2 步：抓取正文（可随时关闭窗口，下次运行自动续抓） ====="
Jar @('fetch', '--biz', $biz, '--urls', $urls, '--out', $out, '--delay', $delay) | Out-Null
Write-Host ""
Write-Host "完成，输出在：$out"
Done 0
