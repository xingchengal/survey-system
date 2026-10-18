# ============================================================
# 压测编排：登录 → 取题目 id → 跑 6 个只读统计接口 → 输出结果
#
# 用法：
#   pwsh -File run-bench.ps1 -SurveyId 909 -Username admin -Password ***
#   pwsh -File run-bench.ps1 -SurveyId 909 -Token <jwt> -Base http://localhost:8082 -Out result-nocache.txt
#
# 前提：夹具已灌入（bench-fill.sql），且目标实例已启动。
# ★ 登录接口有 10 次/分/IP 限流，所以整个脚本只登录一次；
#   要反复跑对照实验时，把第一次拿到的 token 用 -Token 传进来更省事。
# ============================================================
param(
    [string]$Base = 'http://localhost:8081',
    [int]$SurveyId,
    [string]$Token,
    [string]$Username,
    [string]$Password,
    [string]$Out = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $SurveyId) {
    Write-Host '缺少 -SurveyId（bench-fill.sql 执行完会打印 survey_id）'; exit 2
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bench = Join-Path $scriptDir 'Bench.java'
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { '' }
$java = if ($javaHome) { Join-Path $javaHome 'bin\java.exe' } else { 'java' }
if (-not (Test-Path $bench)) { Write-Host "找不到 $bench"; exit 2 }
if (-not (Test-Path $java) -and -not (Get-Command $java -ErrorAction SilentlyContinue)) {
    Write-Host '找不到 java：请先设置 JAVA_HOME（本项目需要 JDK 17）'; exit 2
}

# ---------- 校验 java 版本 ----------
# ★ 这一步不能省：Bench.java 用的是**单文件源码模式**（`java Bench.java`），JDK 11+ 才有。
#   PATH 上如果是 JDK 8（本项目的头号环境坑），`java Bench.java` 会直接报错，
#   而脚本在 `2>&1 | Tee-Object` 里会把它吞成一行普通输出 —— 表现为"跑完了但没有任何 QPS"。
$verLine = (& $java -version 2>&1 | Select-Object -First 1)
if ($verLine -notmatch '"(1[7-9]|[2-9][0-9])') {
    Write-Host "需要 JDK 17+（单文件源码模式）。当前: $verLine"
    Write-Host '提示：先 `$env:JAVA_HOME=''C:\Users\1\.jdks\ms-17.0.20.1''` 再跑本脚本'
    exit 2
}
Write-Host "java: $verLine"

# ---------- 登录（只登录一次） ----------
if (-not $Token) {
    if (-not $Username) { Write-Host '需要 -Token，或 -Username/-Password'; exit 2 }
    $body = @{ username = $Username; password = $Password } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$Base/api/user/login" -Method Post -ContentType 'application/json' -Body $body
    $Token = $login.token
    if (-not $Token) { Write-Host '登录失败：没拿到 token'; exit 3 }
    Write-Host "登录成功（$($login.user.username) / $($login.user.role)）"
}

$headers = @{ Authorization = "Bearer $Token" }

# ---------- 取题目 id（自动挑一道单选、一道填空） ----------
$questions = Invoke-RestMethod -Uri "$Base/api/question/list/$SurveyId" -Headers $headers
$single = ($questions | Where-Object { $_.type -eq 1 } | Select-Object -First 1).id
$text = ($questions | Where-Object { $_.type -eq 3 } | Select-Object -First 1).id
if (-not $single -or -not $text) {
    Write-Host '夹具里没找到单选(type=1)或填空(type=3)题目 —— 确认 -SurveyId 指对了'; exit 3
}
Write-Host "问卷 $SurveyId：单选 qid=$single，填空 qid=$text"

# ---------- 用例 ----------
$cases = @(
    @{ label = 'A 单题选项分布 statistics/{qid}';          url = "$Base/api/answer/statistics/$single";      c = 32; n = 2000 },
    @{ label = 'B 答卷数 count/{sid}';                     url = "$Base/api/answer/count/$SurveyId";          c = 32; n = 2000 },
    @{ label = 'C 填空文本 texts/{qid}';                   url = "$Base/api/answer/texts/$text";              c = 16; n = 600 },
    @{ label = 'D 整份问卷统计 statistics/survey/{sid}';   url = "$Base/api/answer/statistics/survey/$SurveyId"; c = 8; n = 300 },
    @{ label = 'E 答卷列表分页 list/{sid}';                url = "$Base/api/answer/list/$SurveyId`?pageNum=1&pageSize=10"; c = 8; n = 300 },
    @{ label = 'F 我的统计 my-statistics';                 url = "$Base/api/survey/my-statistics";            c = 16; n = 800 }
)

if (-not $Out) { $Out = Join-Path $scriptDir 'bench-result.txt' }
"### $Base / 问卷 $SurveyId / $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Tee-Object -FilePath $Out

foreach ($x in $cases) {
    Write-Host ''
    "=== $($x.label) ===" | Tee-Object -FilePath $Out -Append
    & $java '-Dstdout.encoding=UTF-8' $bench --url $x.url --token $Token --method GET `
        --concurrency $x.c --requests $x.n --warmup 100 --label $x.label 2>&1 |
        Tee-Object -FilePath $Out -Append
}

Write-Host ''
Write-Host "结果已写入: $Out"
