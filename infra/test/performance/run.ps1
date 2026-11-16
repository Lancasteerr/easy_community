#requires -Version 7.0
param(
    [ValidateRange(3, 300)][int]$Seconds = 60,
    [ValidateRange(1, 5)][int]$Rounds = 3,
    [ValidateSet('All', 'Core', 'Like')][string]$Suite = 'All',
    [string]$LikeRates = '60,120'
)
# 所有性能实验统一入口；仅使用resume-perf专用Compose与测试库，逐轮归档到target之外。
$ErrorActionPreference = 'Stop'
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
$output = Join-Path $artifactRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $output | Out-Null
$metadata = [ordered]@{
    startedAt = [DateTimeOffset]::UtcNow.ToString('o'); status = 'failed'
    revision = (& git -C $repoRoot rev-parse HEAD); workingTree = @(& git -C $repoRoot status --porcelain)
    seconds = $Seconds; rounds = $Rounds; suite = $Suite; likeRates = $LikeRates
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
    $metadata.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $output 'metadata.json') -Encoding utf8
    foreach ($entry in $previous.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    $lock.Dispose()
    Write-Output "性能产物已保存：$output；状态：$($metadata.status)"
}
& (Join-Path $PSScriptRoot 'summarize.ps1') -RunPath $output
& (Join-Path $PSScriptRoot 'verify.ps1') -RunPath $output
