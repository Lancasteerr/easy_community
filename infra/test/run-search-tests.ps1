param(
    [string]$Tests = '*Search*Tests,*Index*Tests,*Rank*Tests,ArticleMysqlIntegrationTests,ArticleViewMysqlIntegrationTests,ArticleHttpIntegrationTests,LikeHttpIntegrationTests,ArticleListHydratorTests,ArticleQueryServiceTests',
    [switch]$Verify,
    [switch]$Faults
)
# 独立四服务环境；凭证仅写入Git忽略的本机文件，测试日志不打印变量值。
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$backendPath = Join-Path $repoRoot 'backend'
$testEnvFile = Join-Path $backendPath '.env.search-it'
if (-not (Test-Path -LiteralPath $testEnvFile)) {
    Set-Content -LiteralPath $testEnvFile -Value ('IT_SERVICE_PASSWORD=' + [guid]::NewGuid().ToString('N')) -Encoding utf8
}
$testPassword = (Get-Content -LiteralPath $testEnvFile -Raw).Trim().Split('=', 2)[1]
& docker compose --env-file $testEnvFile -f (Join-Path $PSScriptRoot 'search-compose.yaml') up -d --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw '检索测试依赖未就绪' }
$settings = @{
    IT_SEARCH='true'; IT_ARTICLE='true'; IT_REDIS='true'; IT_LIKE='true'
    IT_SEARCH_FAULTS=$Faults.IsPresent.ToString().ToLowerInvariant()
    IT_DB_URL='jdbc:mysql://127.0.0.1:17806/easy_community_search_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    IT_DB_USERNAME='root'; IT_DB_PASSWORD=$testPassword
    IT_REDIS_HOST='127.0.0.1'; IT_REDIS_PORT='17879'; IT_REDIS_PASSWORD=$testPassword
    IT_RABBITMQ_HOST='127.0.0.1'; IT_RABBITMQ_PORT='17872'
    IT_RABBITMQ_USERNAME='search_test'; IT_RABBITMQ_PASSWORD=$testPassword; IT_RABBITMQ_VHOST='easy_community_search_test'
    RABBITMQ_HOST='127.0.0.1'; RABBITMQ_PORT='17872'
    RABBITMQ_USERNAME='search_test'; RABBITMQ_PASSWORD=$testPassword; RABBITMQ_VHOST='easy_community_search_test'
    IT_ES_URL='http://127.0.0.1:19200'; IT_ES_USERNAME='elastic'; IT_ES_PASSWORD=$testPassword
    ES_URL='http://127.0.0.1:19200'; ES_USERNAME='elastic'; ES_PASSWORD=$testPassword
}
$previous = @{}
foreach ($item in $settings.GetEnumerator()) {
    $previous[$item.Key] = [Environment]::GetEnvironmentVariable($item.Key, 'Process')
    [Environment]::SetEnvironmentVariable($item.Key, $item.Value, 'Process')
}
Push-Location $backendPath
try {
    $targetPath = Join-Path $backendPath 'target'
    $jvmDirectory = if ($IsWindows) { Join-Path ([IO.Path]::GetPathRoot($repoRoot)) 'temp' } else { Join-Path $targetPath 'jvm-tmp' }
    New-Item -ItemType Directory -Force -Path $jvmDirectory | Out-Null
    $argLine = "-Djdk.net.unixdomain.tmpdir=$jvmDirectory"
    # 预挂载本机已解析的代理，避免JDK25测试启动时反复自附加；无缓存时沿用Mockito默认行为。
    $agent = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.m2/repository/net/bytebuddy/byte-buddy-agent') -Filter 'byte-buddy-agent-*.jar' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($agent) { $argLine += " -javaagent:$($agent.FullName)" }
    $arguments = @('-q', "-DargLine=$argLine")
    if ($Tests) { $arguments += "-Dtest=$Tests" }
    $arguments += $(if ($Verify) { @('clean','verify') } else { @('test') })
    $logPath = Join-Path ([IO.Path]::GetTempPath()) ('search-it-' + [guid]::NewGuid().ToString('N') + '.log')
    & mvn @arguments *> $logPath
    $buildExit = $LASTEXITCODE
    New-Item -ItemType Directory -Force -Path $targetPath | Out-Null
    $destination = Join-Path $targetPath 'search-integration.log'
    Move-Item -LiteralPath $logPath -Destination $destination -Force
    if ($buildExit -ne 0) { Get-Content -LiteralPath $destination -Tail 80; throw "检索验证失败，日志：$destination" }
    Write-Output "检索验证通过，报告目录：$(Join-Path $targetPath 'surefire-reports')"
} finally {
    Pop-Location
    foreach ($item in $previous.GetEnumerator()) { [Environment]::SetEnvironmentVariable($item.Key, $item.Value, 'Process') }
}
