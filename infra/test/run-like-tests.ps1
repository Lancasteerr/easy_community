param(
    [string]$Tests = 'Like*Tests',
    [switch]$Load,
    [switch]$Faults,
    [switch]$Verify,
    [switch]$Redis72
)
# 所有依赖仅属于easy-community-like-it项目，禁止指向开发库或共享vhost。
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$backendPath = Join-Path $repoRoot 'backend'
$targetPath = Join-Path $backendPath 'target'
New-Item -ItemType Directory -Force -Path $targetPath | Out-Null
$testEnvFile = Join-Path $backendPath '.env.like-it'
if (-not (Test-Path -LiteralPath $testEnvFile)) {
    Set-Content -LiteralPath $testEnvFile -Value ('IT_SERVICE_PASSWORD=' + [guid]::NewGuid().ToString('N')) -Encoding utf8
}
$testPassword = (Get-Content -LiteralPath $testEnvFile -Raw).Trim().Split('=', 2)[1]
$composeFile = Join-Path $PSScriptRoot 'like-compose.yaml'
if ($Redis72 -and ($Faults -or $Load -or $Verify)) { throw 'Redis72只用于独立脚本兼容测试，不能与故障、容量或全量验证混用' }
& docker compose --env-file $testEnvFile -f $composeFile up -d --wait --wait-timeout 120
if ($LASTEXITCODE -ne 0) { throw '专用点赞测试依赖未就绪' }
if ($Redis72) {
    & docker compose --env-file $testEnvFile -f $composeFile --profile compatibility up -d --wait redis72
    if ($LASTEXITCODE -ne 0) { throw 'Redis7.2兼容实例未就绪' }
}
$settings = @{
    IT_LIKE = 'true'; IT_REDIS = 'true'
    IT_DB_URL = 'jdbc:mysql://127.0.0.1:17706/easy_community_like_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    IT_DB_USERNAME = 'root'; IT_DB_PASSWORD = $testPassword
    IT_REDIS_HOST = '127.0.0.1'; IT_REDIS_PORT = '17779'; IT_REDIS_PASSWORD = $testPassword
    IT_RABBITMQ_HOST = '127.0.0.1'; IT_RABBITMQ_PORT = '17772'
    IT_RABBITMQ_USERNAME = 'like_test'; IT_RABBITMQ_PASSWORD = $testPassword; IT_RABBITMQ_VHOST = 'easy_community_like_test'
    RABBITMQ_HOST = '127.0.0.1'; RABBITMQ_PORT = '17772'
    RABBITMQ_USERNAME = 'like_test'; RABBITMQ_PASSWORD = $testPassword; RABBITMQ_VHOST = 'easy_community_like_test'
    IT_LIKE_LOAD = $Load.IsPresent.ToString().ToLowerInvariant()
    IT_LIKE_FAULTS = $Faults.IsPresent.ToString().ToLowerInvariant()
    IT_ARTICLE = $Verify.IsPresent.ToString().ToLowerInvariant()
}
if ($Redis72) { $settings.IT_REDIS_PORT = '17778'; $Tests = 'LikeAllocatorIntegrationTests,LikeCacheIntegrationTests' }
$previous = @{}
foreach ($entry in $settings.GetEnumerator()) {
    $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
}
Push-Location $backendPath
try {
    # Windows JDK25临时域套接字路径需短ASCII目录；仅本次测试JVM使用，不改系统设置。
    $jvmDirectory = if ($IsWindows) { Join-Path ([IO.Path]::GetPathRoot($repoRoot)) 'temp' } else { Join-Path $targetPath 'jvm-tmp' }
    New-Item -ItemType Directory -Force -Path $jvmDirectory | Out-Null
    # clean会删除target，日志暂放系统临时目录，构建结束后移回报告目录。
    $logPath = Join-Path ([IO.Path]::GetTempPath()) ('like-it-' + [guid]::NewGuid().ToString('N') + '.log')
    $mavenArguments = @('-q', "-DargLine=-Djdk.net.unixdomain.tmpdir=$jvmDirectory")
    if ($Verify) {
        if ($Tests -ne 'Like*Tests') { $mavenArguments += "-Dtest=$Tests" }
        $mavenArguments += @('clean', 'verify')
    } else { $mavenArguments += @("-Dtest=$Tests", 'test') }
    & mvn @mavenArguments *> $logPath
    $buildExit = $LASTEXITCODE
    New-Item -ItemType Directory -Force -Path $targetPath | Out-Null
    Move-Item -LiteralPath $logPath -Destination (Join-Path $targetPath 'like-integration.log') -Force
    $logPath = Join-Path $targetPath 'like-integration.log'
    if ($buildExit -ne 0) { Get-Content -LiteralPath $logPath -Tail 70; throw "点赞测试失败，日志：$logPath" }
    Write-Output "点赞测试通过，报告目录：$(Join-Path $targetPath 'surefire-reports')"
} finally {
    Pop-Location
    foreach ($entry in $previous.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
}
