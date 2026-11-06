# 对已构建的实际JAR执行非Web维护命令；只允许专用测试容器及随机签发器命名空间。
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$backendPath = Join-Path $repoRoot 'backend'
$jarPath = Join-Path $backendPath 'target/backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先完成后端package或verify构建' }
foreach ($service in @('mysql', 'redis', 'rabbitmq')) {
    $container = "easy-community-like-it-$service-1"
    $metadata = (& docker inspect $container | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0 -or $metadata.Config.Labels.'ec.like.test' -ne 'true' -or
        $metadata.Config.Labels.'com.docker.compose.project' -ne 'easy-community-like-it') { throw '只能操作专用测试容器' }
}
$password = (Get-Content -LiteralPath (Join-Path $backendPath '.env.like-it') -Raw).Trim().Split('=', 2)[1]
$namespace = 'ec:test:cli:' + [guid]::NewGuid().ToString('N') + ':like'
$settings = @{
    IT_DB_URL = 'jdbc:mysql://127.0.0.1:17706/easy_community_like_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    IT_DB_USERNAME = 'root'; IT_DB_PASSWORD = $password
    IT_REDIS_HOST = '127.0.0.1'; IT_REDIS_PORT = '17779'; IT_REDIS_PASSWORD = $password
    RABBITMQ_HOST = '127.0.0.1'; RABBITMQ_PORT = '17772'; RABBITMQ_USERNAME = 'like_test'
    RABBITMQ_PASSWORD = $password; RABBITMQ_VHOST = 'easy_community_like_test'
}
$previous = @{}
foreach ($item in $settings.GetEnumerator()) {
    $previous[$item.Key] = [Environment]::GetEnvironmentVariable($item.Key, 'Process')
    [Environment]::SetEnvironmentVariable($item.Key, $item.Value, 'Process')
}
$jvmDirectory = if ($IsWindows) { Join-Path ([IO.Path]::GetPathRoot($repoRoot)) 'temp' } else { Join-Path $backendPath 'target/jvm-tmp' }
New-Item -ItemType Directory -Force -Path $jvmDirectory | Out-Null
$baseArguments = @("-Djdk.net.unixdomain.tmpdir=$jvmDirectory", '-jar', $jarPath, '--spring.profiles.active=test',
    '--spring.config.additional-location=file:src/test/resources/application-test.properties',
    '--spring.main.web-application-type=none',
    "--app.like.namespace=$namespace")
function Invoke-LikeCommand([string]$Action, [string[]]$Extra = @(), [bool]$ExpectedSuccess = $true) {
    $log = Join-Path $backendPath "target/like-cli-$Action.log"
    # 除显式重放外使用不可达Broker端口，验证维护命令不会启动消费者或隐式连接MQ。
    if ($Action -ne 'replay') { $Extra = @('--spring.rabbitmq.port=1') + $Extra }
    & java @baseArguments "--app.like.maintenance=$Action" @Extra *> $log
    if (($LASTEXITCODE -eq 0) -ne $ExpectedSuccess) { throw "维护动作退出码不符：$Action，日志：$log" }
    if ($ExpectedSuccess) {
        $json = Get-Content -LiteralPath $log | Where-Object { $_ -match '^\{.*"action"\s*:' } | Select-Object -Last 1
        if (-not $json) { throw "维护动作没有JSON结果：$Action" }
        return ($json | ConvertFrom-Json).result
    }
}
Push-Location $backendPath
try {
    & (Join-Path $repoRoot 'infra/dev/like-topology.ps1') -Namespace $namespace -ManagementUrl http://127.0.0.1:17773
    Invoke-LikeCommand status -ExpectedSuccess $false
    $epoch = & docker exec easy-community-like-it-mysql-1 sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT epoch FROM easy_community_like_test.like_version_epoch WHERE id=1"'
    if ($LASTEXITCODE -ne 0 -or $epoch -notmatch '^\d+$') { throw '无法读取测试纪元' }
    $recovered = Invoke-LikeCommand recover @("--app.like.expected-epoch=$epoch")
    $status = Invoke-LikeCommand status
    if (-not $status.ready -or $status.sequence -ne '0' -or $recovered.epoch -ne $status.sqlEpoch) { throw '恢复或无副作用状态检查失败' }
    Invoke-LikeCommand pause | Out-Null
    Invoke-LikeCommand status -ExpectedSuccess $false
    Invoke-LikeCommand recover @("--app.like.expected-epoch=$($recovered.epoch)") | Out-Null
    Invoke-LikeCommand status | Out-Null
    Invoke-LikeCommand audit | Out-Null
    Invoke-LikeCommand replay @('--app.like.replay-limit=1') | Out-Null
    Write-Output "非Web维护JAR烟测通过：$namespace"
} finally {
    Pop-Location
    foreach ($item in $previous.GetEnumerator()) { [Environment]::SetEnvironmentVariable($item.Key, $item.Value, 'Process') }
    # 随机测试Key和空队列留到专用Compose项目down -v时一起销毁，不扫描共享Redis。
}
