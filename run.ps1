# 一键运行（由 run.bat 调用）：选公众号 → 按进度自动决定要不要滚动 → 抓正文
param(
    [string]$Proc = "",      # 仅测试用：覆盖要识别的微信进程名
    [switch]$NoClipboard     # 仅测试用：不读取剪贴板
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Set-Location $PSScriptRoot
$Host.UI.RawUI.WindowTitle = "公众号文章归档"
$jar = "target\wx-archiver.jar"
$cfgFile = Join-Path $PSScriptRoot "config.json"

# ---------------------------------------------------------------- 小工具

function Ask([string]$prompt, [string]$default) {
    $v = Read-Host "$prompt [回车默认 $default]"
    if ([string]::IsNullOrWhiteSpace($v)) { return $default }
    return $v.Trim()
}

# 是/否：直接回车 = 是；只有输入 N 再回车才算否
function Yes([string]$prompt) {
    $v = Read-Host "$prompt [回车=是，输入 N 回车=否]"
    return -not ($v -match '^\s*[Nn]')
}

# 调 jar：输出直接显示在窗口里，返回退出码
function Jar([string[]]$cmd) {
    if ($Proc -and ($cmd[0] -in @('scroll', 'scan'))) { $cmd += @('--proc', $Proc) }
    & java -jar $jar @cmd | Out-Host
    return $LASTEXITCODE
}

# 调 jar 并取回 key=value 输出
function JarKv([string[]]$cmd) {
    $lines = & java -jar $jar @cmd
    $kv = @{ exit = $LASTEXITCODE; text = ($lines -join "`n") }
    foreach ($l in $lines) { if ($l -match '^(\w+)=(.*)$') { $kv[$Matches[1]] = $Matches[2] } }
    return $kv
}

function Done([int]$code = 0) {
    Write-Host ""
    Read-Host "按回车关闭" | Out-Null
    exit $code
}

function SaveCfg {
    $script:cfg.accounts = @($script:accounts)
    $script:cfg | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $cfgFile -Encoding UTF8
}

function CountNotes([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return 0 }
    return @(Get-ChildItem -LiteralPath $dir -Filter *.md | Where-Object { $_.Name -ne '00-索引.md' }).Count
}

# ---------------------------------------------------------------- 添加公众号

function AddAccount {
    $url = ""
    if (-not $NoClipboard) {
        $clip = Get-Clipboard -Raw -ErrorAction SilentlyContinue
        if ($clip -match 'https?://mp\.weixin\.qq\.com/[^\s"<>]+') {
            $found = $Matches[0]
            Write-Host "剪贴板里有一篇公众号文章链接：$found"
            if (Yes "用它来识别公众号？") { $url = $found }
        }
    }
    while (-not $url) {
        Write-Host "在微信里打开这个公众号的任意一篇文章 → 右上角「…」→「复制链接」，粘贴到下面。"
        $url = (Read-Host "文章链接").Trim()
    }
    Write-Host "正在识别公众号…"
    $r = JarKv @('resolve', '--url', $url)
    if ($r.exit -ne 0 -or -not $r.biz) { Write-Host $r.text; Done 1 }
    $name = ($r.name -replace '[\\/:*?"<>|]', ' ').Trim()
    Write-Host "识别到公众号：$name"

    $same = $script:accounts | Where-Object { $_.biz -eq $r.biz } | Select-Object -First 1
    if ($same) { Write-Host "（这个号之前已经添加过）"; return $same }

    if (-not $script:cfg.outRoot) {
        $script:cfg.outRoot = Ask "笔记保存到哪个目录（每个公众号一个子文件夹）" (Join-Path $PSScriptRoot "out")
    }
    $acc = [pscustomobject]@{ name = $name; biz = $r.biz; out = (Join-Path $script:cfg.outRoot $name) }
    $script:accounts += $acc
    SaveCfg
    Write-Host "已保存，下次直接回车就能选它。"
    return $acc
}

# ---------------------------------------------------------------- 收集链接

function CollectLinks($acc, [string]$urls, [bool]$update) {
    Write-Host ""
    Write-Host "请在微信电脑版里打开「$($acc.name)」的主页。"
    if ($update) {
        Write-Host "提示：看到已经抓过的旧文章时，动一下鼠标即可停止，程序会接着扫描新链接。"
    }
    $auto = $true
    while ($auto) {
        Read-Host "准备好后按回车，然后把鼠标放到微信的文章列表上别动（静止 3 秒自动开始）" | Out-Null
        $cmd = @('scroll', '--biz', $acc.biz, '--out', $urls)
        if ($update) { $cmd += @('--partial-ok', 'true') }
        if ((Jar $cmd) -eq 0) { return }
        $ans = Read-Host "滚动没有完成。[回车=重试，输入 M 回车=改为手动滚，输入 Q 回车=退出]"
        if ($ans -match '^\s*[Qq]') { Done 1 }
        if ($ans -match '^\s*[Mm]') { $auto = $false }
    }
    Read-Host "请手动把历史文章一直滚到底（直到不再加载新文章），然后按回车" | Out-Null
    Jar @('scan', '--biz', $acc.biz, '--out', $urls) | Out-Null
}

# ---------------------------------------------------------------- 主流程

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "[错误] 没找到 java，请先安装 JDK 17+"
    Done 1
}
if (-not (Test-Path $jar)) {
    Write-Host "首次运行，正在编译..."
    & mvn -q -B package | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Host "[错误] 编译失败"; Done 1 }
}

$cfg = if (Test-Path -LiteralPath $cfgFile) { Get-Content -LiteralPath $cfgFile -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
if (-not $cfg) { $cfg = [pscustomobject]@{ outRoot = ""; delay = 3; accounts = @() } }
$accounts = @($cfg.accounts | Where-Object { $_ })

# 选公众号
$acc = $null
if ($accounts.Count -gt 0) {
    Write-Host "已保存的公众号："
    for ($i = 0; $i -lt $accounts.Count; $i++) {
        Write-Host ("  {0}) {1}（已抓 {2} 篇）" -f ($i + 1), $accounts[$i].name, (CountNotes $accounts[$i].out))
    }
    Write-Host "  N) 添加新的公众号"
    $c = Read-Host "选择 [回车=1]"
    if ([string]::IsNullOrWhiteSpace($c)) { $c = "1" }
    if ($c -match '^\d+$' -and [int]$c -ge 1 -and [int]$c -le $accounts.Count) { $acc = $accounts[[int]$c - 1] }
}
if (-not $acc) { $acc = AddAccount }

New-Item -ItemType Directory -Force -Path $acc.out | Out-Null
$urls = Join-Path $acc.out "urls.txt"
$delay = if ($cfg.delay) { [string]$cfg.delay } else { "3" }

# 按进度决定要不要滚动
$st = JarKv @('status', '--urls', $urls, '--out', $acc.out)
$total = [int]$st.total
$pending = [int]$st.pending
Write-Host ""
if ($total -eq 0) {
    Write-Host "第一次抓「$($acc.name)」：先把主页滚到底，收集全部文章链接。"
    CollectLinks $acc $urls $false
} elseif ($pending -gt 0) {
    Write-Host "「$($acc.name)」上次还有 $pending 篇没抓完（清单共 $total 篇），接着抓。"
} else {
    Write-Host "「$($acc.name)」清单里的 $total 篇都抓完了。"
    if (-not (Yes "滚动主页检查有没有新文章？")) { Done 0 }
    CollectLinks $acc $urls $true
}

$st = JarKv @('status', '--urls', $urls, '--out', $acc.out)
if ([int]$st.total -eq 0) {
    Write-Host "[提示] 没扫到任何链接：确认已在微信里打开该公众号的主页并滚动过"
    Done 1
}
if ([int]$st.pending -eq 0) {
    Write-Host "没有发现新文章。"
    Done 0
}

Write-Host ""
Write-Host "===== 抓取正文：$($st.pending) 篇（可随时关闭窗口，下次运行自动续抓） ====="
Jar @('fetch', '--biz', $acc.biz, '--urls', $urls, '--out', $acc.out, '--delay', $delay) | Out-Null
Write-Host ""
Write-Host "完成，输出在：$($acc.out)"
Done 0
