#requires -Version 7.0
param(
    [ValidateRange(3, 300)][int]$Seconds = 60,
    [ValidateRange(1, 5)][int]$Rounds = 3,
    [ValidateSet('All', 'Core', 'Like')][string]$Suite = 'All',
    [string]$LikeRates = '60,120',
    [switch]$CompareLikeHistory
)
# 所有性能实验统一入口；仅使用resume-perf专用Compose与测试库，逐轮归档到target之外。
$ErrorActionPreference = 'Stop'
if ($CompareLikeHistory -and ($Suite -ne 'Like' -or $LikeRates -ne '60,120')) { throw '历史对比仅支持Suite Like及60,120主阶梯；240探索固定追加' }
if ($CompareLikeHistory -and -not (Get-Command python -ErrorAction SilentlyContinue)) { throw '历史对比需要Python及matplotlib' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$backend = Join-Path $repoRoot 'backend'
$artifactRoot = Join-Path $repoRoot 'artifacts/performance'
$jvmDirectory = if ($IsWindows) { Join-Path ([IO.Path]::GetPathRoot($repoRoot)) 'temp' }
    else { Join-Path $artifactRoot 'jvm-tmp' }
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
foreach ($command in @('git', 'java', 'mvn', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "缺少命令：$command" }
}
if ($LikeRates -notmatch '^\d+(,\d+)*$') { throw 'LikeRates需为逗号分隔的正整数' }
& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker引擎不可用' }
$lock = [IO.File]::Open((Join-Path $artifactRoot 'running.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$previous = @{}
$previousContainers = @(& docker ps --filter label=com.docker.compose.project=easy-community-resume-perf --format '{{.ID}}')
$output = Join-Path $artifactRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $output | Out-Null
$metadata = [ordered]@{
    startedAt = [DateTimeOffset]::UtcNow.ToString('o'); status = 'failed'
    revision = (& git -C $repoRoot rev-parse HEAD); workingTree = @(& git -C $repoRoot status --porcelain)
    seconds = $Seconds; rounds = $Rounds; suite = $Suite; likeRates = $LikeRates
    compareLikeHistory = $CompareLikeHistory.IsPresent; purpose = $(if ($Seconds -lt 60) { "smoke" } else { "measurement" })
    os = [Runtime.InteropServices.RuntimeInformation]::OSDescription; logicalProcessors = [Environment]::ProcessorCount
    docker = (& docker info --format 'Engine={{.ServerVersion}} CPUs={{.NCPU}} Memory={{.MemTotal}}')
    applicationLimit = '每个测试JVM Xmx768m；未施加操作系统CPU上限；客户端与应用同机'
    results = @()
}
function Set-TestVariable([string]$Name, [string]$Value) {
    if (-not $previous.ContainsKey($Name)) { $previous[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Invoke-Measurement([string]$Test, [string]$Report, [string]$RoundPath, [string]$LogName) {
    $began = [DateTimeOffset]::UtcNow
    $arguments = @('-q', "-Dtest=$Test", "-DargLine=-Xmx768m -Djdk.net.unixdomain.tmpdir=$jvmDirectory", 'test')
    & mvn -f (Join-Path $backend 'pom.xml') @arguments *> (Join-Path $RoundPath $LogName)
    $exitCode = $LASTEXITCODE
    $reportDirectory = Join-Path $backend 'target/surefire-reports'
    # 只归档本次选中的套件，避免把target中其他旧结果拼进本次验收。
    Get-ChildItem -LiteralPath $reportDirectory -Filter "*$Test*" -ErrorAction SilentlyContinue |
        Copy-Item -Destination $RoundPath
    if ($exitCode -ne 0) { throw "测试失败：$Test，日志已保存到 $RoundPath" }
    $path = Join-Path $RoundPath $Report
    if (-not (Test-Path -LiteralPath $path)) { throw "本轮未生成 $Report" }
    $result = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    # PowerShell会将部分ISO字符串自动转为DateTime；再次Parse会丢失UTC种类，需分别处理。
    $recordedAt = if ($result.recordedAt -is [DateTime]) { [DateTimeOffset]::new($result.recordedAt) }
        else { [DateTimeOffset]::Parse([string]$result.recordedAt, [Globalization.CultureInfo]::InvariantCulture) }
    $suiteFile = Get-ChildItem -LiteralPath $RoundPath -Filter "TEST-*$Test.xml" | Select-Object -First 1
    if (-not $suiteFile) { throw '没有实际执行的JUnit报告' }
    [xml]$xml = Get-Content -LiteralPath $suiteFile.FullName -Raw
    if ($recordedAt -lt $began -or [int]$xml.testsuite.tests -ne 1 -or
        [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0) {
        throw '拒绝旧报告、跳过项或未通过的测量'
    }
    if ($Report -eq 'core.json' -and $result.passed -ne $true) { throw 'Core未完成全部验证' }
    if ($Report -eq 'like-capacity.json' -and $result.sqlAuditConsistent -ne $true) { throw '点赞SQL对账未通过' }
}
try {
    if ($CompareLikeHistory) {
        & python (Join-Path $PSScriptRoot 'like_history.py') baseline --root $artifactRoot --output (Join-Path $output 'baseline-manifest.json')
        if ($LASTEXITCODE -ne 0) { throw '历史原始样本校验失败，未启动压测' }
    }
    $credentials = Join-Path $backend '.env.resume-perf'
    if (-not (Test-Path -LiteralPath $credentials)) {
        Set-Content -LiteralPath $credentials -Value ('IT_SERVICE_PASSWORD=' + [guid]::NewGuid().ToString('N')) -Encoding utf8
    }
    $password = (Get-Content -LiteralPath $credentials -Raw).Trim().Split('=', 2)[1]
    & docker compose --env-file $credentials -f (Join-Path $PSScriptRoot 'compose.yaml') up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw '独立压测环境启动失败' }
    $containerIds = @(& docker compose --env-file $credentials -f (Join-Path $PSScriptRoot 'compose.yaml') ps -q)
    $metadata.containers = @(& docker inspect --format '{{.Name}} {{.Config.Image}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}} Mounts={{json .Mounts}}' @containerIds)
    $settings = @{
        IT_RESUME_PERF='true'; IT_LIKE_LOAD='true'; IT_PERF_SECONDS="$Seconds"; IT_LIKE_LOAD_SECONDS="$Seconds"
        IT_LIKE_LOAD_SCENARIOS='uniform,hot,backlog'; IT_LIKE_LOAD_RATES=$LikeRates
        IT_LIKE_HISTORY_COMPARISON=$CompareLikeHistory.IsPresent.ToString().ToLowerInvariant()
        IT_RABBITMQ_MANAGEMENT_URL='http://127.0.0.1:17973'
        IT_DB_URL='jdbc:mysql://127.0.0.1:17906/easy_community_resume_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
        IT_DB_USERNAME='root'; IT_DB_PASSWORD=$password
        IT_REDIS_HOST='127.0.0.1'; IT_REDIS_PORT='17979'; IT_REDIS_PASSWORD=$password
        RABBITMQ_HOST='127.0.0.1'; RABBITMQ_PORT='17972'; RABBITMQ_USERNAME='resume_test'
        RABBITMQ_PASSWORD=$password; RABBITMQ_VHOST='easy_community_resume_test'; IT_RABBITMQ_VHOST='easy_community_resume_test'
        ES_URL='http://127.0.0.1:19300'; ES_USERNAME='elastic'; ES_PASSWORD=$password
        SPRING_PROFILES_ACTIVE='test'; ADMIN_BOOTSTRAP_ENABLED='false'
    }
    foreach ($entry in $settings.GetEnumerator()) { Set-TestVariable $entry.Key $entry.Value }
    # 保存当时的基准源码与脚本，报告即使基于未提交工作区也可复核。
    $sources = Join-Path $output 'sources'
    New-Item -ItemType Directory -Path $sources | Out-Null
    Copy-Item -LiteralPath $PSScriptRoot -Destination (Join-Path $sources 'scripts') -Recurse
    Copy-Item -LiteralPath (Join-Path $backend 'src/test/java/com/febrie/backend/performance') -Destination (Join-Path $sources 'java') -Recurse
    Copy-Item -LiteralPath (Join-Path $backend 'src/test/java/com/febrie/backend/article/like/LikeCapacityTests.java') -Destination $sources
    if ($CompareLikeHistory) {
        Copy-Item -LiteralPath (Join-Path $backend 'src/test/java/com/febrie/backend/article/like') -Destination (Join-Path $sources 'like-tests') -Recurse
        Copy-Item -LiteralPath (Join-Path $backend 'src/main/java/com/febrie/backend/article/like') -Destination (Join-Path $sources 'like-production') -Recurse
        $hashes = @(Get-ChildItem -LiteralPath $sources -Recurse -File | ForEach-Object {
            @{ path=[IO.Path]::GetRelativePath($sources,$_.FullName); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        })
        ConvertTo-Json -InputObject $hashes -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'source-hashes.json') -Encoding utf8
    }
    New-Item -ItemType Directory -Force -Path $jvmDirectory | Out-Null
    Push-Location $backend
    try {
        for ($round = 1; $round -le $Rounds; $round++) {
            $roundPath = Join-Path $output "round-$round"
            New-Item -ItemType Directory -Path $roundPath | Out-Null
            Set-TestVariable 'IT_PERF_ROUND' "$round"
            Set-TestVariable 'IT_PERF_OUTPUT' $roundPath
            Write-Output "第 $round/$Rounds 轮，结果：$roundPath"
            if ($Suite -in @('All', 'Core')) { Invoke-Measurement 'ResumePerformanceTests' 'core.json' $roundPath 'core.log' }
            if ($Suite -in @('All', 'Like')) { Invoke-Measurement 'LikeCapacityTests' 'like-capacity.json' $roundPath 'like.log' }
            $metadata.results += @{ round = $round; status = 'measured' }
        }
    } finally { Pop-Location }
    $metadata.status = 'measured'
} finally {
    if ($CompareLikeHistory) {
        # 只停止本次启动的专用服务，不删除卷，不停止运行前已有的服务。
        $owned = @(& docker ps --filter label=com.docker.compose.project=easy-community-resume-perf --format '{{.ID}}' | Where-Object { $_ -notin $previousContainers })
        if ($owned.Count -gt 0) {
            & docker stop --timeout 20 @owned
            $metadata.testServicesStopped = ($LASTEXITCODE -eq 0)
        } else { $metadata.testServicesStopped = $true }
    }
    $metadata.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $output 'metadata.json') -Encoding utf8
    foreach ($entry in $previous.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    $lock.Dispose()
    Write-Output "性能产物已保存：$output；状态：$($metadata.status)"
}
& (Join-Path $PSScriptRoot 'summarize.ps1') -RunPath $output
& (Join-Path $PSScriptRoot 'verify.ps1') -RunPath $output

if ($CompareLikeHistory) {
    & python (Join-Path $PSScriptRoot 'like_history.py') verify --run $output
    if ($LASTEXITCODE -ne 0) { throw '新版采集独立复核失败' }
    & python (Join-Path $PSScriptRoot 'like_history.py') compare --run $output
    if ($LASTEXITCODE -ne 0) { throw '历史对比汇总失败' }
}
