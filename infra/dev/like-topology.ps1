param(
    [string]$Namespace = 'ec:dev:like',
    [string]$ManagementUrl = 'http://127.0.0.1:15672',
    [string]$VHost = $env:RABBITMQ_VHOST,
    [long]$QueueBytes = 67108864,
    [ValidateRange(1, 64)][int]$PartitionCount = 4,
    [switch]$RetireLegacyBinding,
    [switch]$VerifyOnly
)
# 只声明本命名空间拓扑，不删除队列、不清消息；密码只从环境读取且不输出。
$ErrorActionPreference = 'Stop'
if ($Namespace -notmatch '^ec:(dev|test|prod)[A-Za-z0-9:_-]*:like$' -or $QueueBytes -lt 4096) { throw '命名空间或容量无效' }
if (-not $VHost -or -not $env:RABBITMQ_USERNAME -or -not $env:RABBITMQ_PASSWORD) { throw '必须提供vhost及RabbitMQ环境凭证' }
$credentialBytes = [Text.Encoding]::UTF8.GetBytes($env:RABBITMQ_USERNAME + ':' + $env:RABBITMQ_PASSWORD)
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String($credentialBytes) }
$encodedVhost = [uri]::EscapeDataString($VHost)
$prefix = $Namespace.Replace(':', '.') + '.article.like.'
if ($VerifyOnly -and $RetireLegacyBinding) { throw '只读核验不能同时移除旧绑定' }
$dead = $prefix + 'dead.q'
$exchange = $prefix + 'command.x'
$dlx = $prefix + 'dead.x'
function Invoke-LikeManagement([string]$Method, [string]$Path, $Body = $null) {
    $arguments = @{ Method = $Method; Uri = ($ManagementUrl.TrimEnd('/') + '/api/' + $Path); Headers = $headers; TimeoutSec = 10 }
    if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = ConvertTo-Json -Depth 8 -Compress -InputObject $Body }
    Invoke-RestMethod @arguments
}
$workArguments = @{
    'x-queue-type' = 'quorum'; 'x-quorum-initial-group-size' = 1; 'x-single-active-consumer' = $true
    'x-overflow' = 'reject-publish'; 'x-max-length-bytes' = $QueueBytes; 'x-delivery-limit' = 20
    'x-dead-letter-exchange' = $dlx; 'x-dead-letter-routing-key' = 'article.like.dead'; 'x-dead-letter-strategy' = 'at-least-once'
}
$deadArguments = @{
    'x-queue-type' = 'quorum'; 'x-quorum-initial-group-size' = 1
    'x-overflow' = 'reject-publish'; 'x-max-length-bytes' = $QueueBytes; 'x-delivery-limit' = -1
}
# 分区队列共用现有死信；容量为每队列预算，总预算随分区数量增加。
$items = @(@{ Name = $dead; Args = $deadArguments; Exchange = $dlx; Route = 'article.like.dead' })
for ($partition = 0; $partition -lt $PartitionCount; $partition++) {
    $items += @{ Name = ($prefix + "command.p$partition.q"); Args = $workArguments; Exchange = $exchange; Route = "article.like.set.p$partition" }
}
if (-not $VerifyOnly) {
    foreach ($name in @($exchange, $dlx)) {
        Invoke-LikeManagement PUT "exchanges/$encodedVhost/$([uri]::EscapeDataString($name))" @{ type = 'direct'; durable = $true; auto_delete = $false; internal = $false; arguments = @{} } | Out-Null
    }
    foreach ($item in $items) {
        $encodedName = [uri]::EscapeDataString($item.Name)
        Invoke-LikeManagement PUT "queues/$encodedVhost/$encodedName" @{ durable = $true; auto_delete = $false; arguments = $item.Args } | Out-Null
        Invoke-LikeManagement POST "bindings/$encodedVhost/e/$([uri]::EscapeDataString($item.Exchange))/q/$encodedName" @{ routing_key = $item.Route; arguments = @{} } | Out-Null
    }
}
foreach ($exchangeName in @($exchange, $dlx)) {
    $declared = Invoke-LikeManagement GET "exchanges/$encodedVhost/$([uri]::EscapeDataString($exchangeName))"
    if ($declared.type -ne 'direct' -or -not $declared.durable -or $declared.auto_delete -or $declared.internal) {
        throw "Exchange类型或持久性错误：$exchangeName"
    }
}
foreach ($item in $items) {
    $encodedName = [uri]::EscapeDataString($item.Name)
    $queue = Invoke-LikeManagement GET "queues/$encodedVhost/$encodedName"
    if ($queue.type -ne 'quorum' -or -not $queue.durable -or $queue.auto_delete) { throw "队列持久性或类型错误：$($item.Name)" }
    foreach ($argument in $item.Args.GetEnumerator()) {
        if ([string]$queue.arguments.($argument.Key) -ne [string]$argument.Value) { throw "队列参数不匹配：$($argument.Key)" }
    }
    foreach ($expiry in @('message-ttl', 'expires')) {
        if ($queue.effective_policy_definition.PSObject.Properties.Name -contains $expiry -or
            $queue.arguments.PSObject.Properties.Name -contains ('x-' + $expiry)) { throw '点赞队列不能配置消息TTL或自动过期' }
    }
    # 防止Operator Policy覆盖安全参数，特别是DLQ有限投递次数导致消息被丢弃。
    foreach ($critical in @('overflow', 'dead-letter-strategy', 'delivery-limit', 'dead-letter-exchange', 'dead-letter-routing-key', 'max-length-bytes')) {
        $configured = $queue.effective_policy_definition.($critical)
        if ($null -ne $configured -and [string]$configured -ne [string]$item.Args.('x-' + $critical)) { throw "生效策略与安全参数冲突：$critical" }
    }
    $bindings = Invoke-LikeManagement GET "queues/$encodedVhost/$encodedName/bindings"
    if (-not ($bindings | Where-Object { $_.source -eq $item.Exchange -and $_.routing_key -eq $item.Route })) { throw "队列绑定缺失：$($item.Name)" }
}
if ($RetireLegacyBinding) {
    # 直接切换只移除旧路由，不删除旧队列或死信；存在在途处理时拒绝操作。
    $legacyName = $prefix + 'command.q'
    $queues = @(Invoke-LikeManagement GET "queues/$encodedVhost")
    $legacy = $queues | Where-Object { $_.name -eq $legacyName }
    if ($legacy) {
        if ($legacy.messages -ne 0 -or $legacy.messages_ready -ne 0 -or $legacy.messages_unacknowledged -ne 0 -or $legacy.consumers -ne 0) {
            throw '旧队列仍有消息或消费者，不能直接切换'
        }
        $encodedLegacy = [uri]::EscapeDataString($legacyName)
        $bindings = @(Invoke-LikeManagement GET "queues/$encodedVhost/$encodedLegacy/bindings")
        foreach ($binding in $bindings | Where-Object { $_.source -eq $exchange -and $_.routing_key -eq 'article.like.set' }) {
            $bindingKey = [uri]::EscapeDataString($binding.properties_key)
            Invoke-LikeManagement DELETE "bindings/$encodedVhost/e/$([uri]::EscapeDataString($exchange))/q/$encodedLegacy/$bindingKey" | Out-Null
        }
        $remaining = @(Invoke-LikeManagement GET "queues/$encodedVhost/$encodedLegacy/bindings")
        if ($remaining | Where-Object { $_.source -eq $exchange -and $_.routing_key -eq 'article.like.set' }) { throw '旧绑定仍然存在' }
    }
}
Write-Output "点赞分区、单活和死信安全参数已核验：$Namespace，分区数=$PartitionCount"
