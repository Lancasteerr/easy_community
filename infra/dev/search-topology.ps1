param(
    [string]$Namespace = 'ec:dev:search',
    [string]$ManagementUrl = 'http://127.0.0.1:15672',
    [string]$VHost = $env:RABBITMQ_VHOST,
    [long]$QueueBytes = 67108864,
    [switch]$VerifyOnly
)
# 只声明本命名空间拓扑，不删除队列、不清消息；密码只从环境读取且不输出。
$ErrorActionPreference = 'Stop'
if ($Namespace -notmatch '^ec:(dev|test|prod)[A-Za-z0-9:_-]*:search$' -or $QueueBytes -lt 4096) { throw '命名空间或容量无效' }
if (-not $VHost -or -not $env:RABBITMQ_USERNAME -or -not $env:RABBITMQ_PASSWORD) { throw '必须提供vhost及RabbitMQ环境凭证' }
$credentialBytes = [Text.Encoding]::UTF8.GetBytes($env:RABBITMQ_USERNAME + ':' + $env:RABBITMQ_PASSWORD)
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String($credentialBytes) }
$encodedVhost = [uri]::EscapeDataString($VHost)
$prefix = $Namespace.Replace(':', '.') + '.article.index.'
$work = $prefix + 'work.q'
$dead = $prefix + 'dead.q'
$exchange = $prefix + 'change.x'
$dlx = $prefix + 'dead.x'
function Invoke-IndexManagement([string]$Method, [string]$Path, $Body = $null) {
    $arguments = @{ Method = $Method; Uri = ($ManagementUrl.TrimEnd('/') + '/api/' + $Path); Headers = $headers; TimeoutSec = 10 }
    if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = ConvertTo-Json -Depth 8 -Compress -InputObject $Body }
    Invoke-RestMethod @arguments
}
$workArguments = @{
    'x-queue-type' = 'quorum'; 'x-quorum-initial-group-size' = 1
    'x-overflow' = 'reject-publish'; 'x-max-length-bytes' = $QueueBytes; 'x-delivery-limit' = 20
    'x-dead-letter-exchange' = $dlx; 'x-dead-letter-routing-key' = 'article.index.dead'; 'x-dead-letter-strategy' = 'at-least-once'
}
$deadArguments = @{
    'x-queue-type' = 'quorum'; 'x-quorum-initial-group-size' = 1
    'x-overflow' = 'reject-publish'; 'x-max-length-bytes' = $QueueBytes; 'x-delivery-limit' = -1
}
if (-not $VerifyOnly) {
    foreach ($name in @($exchange, $dlx)) {
        Invoke-IndexManagement PUT "exchanges/$encodedVhost/$([uri]::EscapeDataString($name))" @{ type = 'direct'; durable = $true; auto_delete = $false; internal = $false; arguments = @{} } | Out-Null
    }
    Invoke-IndexManagement PUT "queues/$encodedVhost/$([uri]::EscapeDataString($work))" @{ durable = $true; auto_delete = $false; arguments = $workArguments } | Out-Null
    Invoke-IndexManagement PUT "queues/$encodedVhost/$([uri]::EscapeDataString($dead))" @{ durable = $true; auto_delete = $false; arguments = $deadArguments } | Out-Null
    Invoke-IndexManagement POST "bindings/$encodedVhost/e/$([uri]::EscapeDataString($exchange))/q/$([uri]::EscapeDataString($work))" @{ routing_key = 'article.index.changed'; arguments = @{} } | Out-Null
    Invoke-IndexManagement POST "bindings/$encodedVhost/e/$([uri]::EscapeDataString($dlx))/q/$([uri]::EscapeDataString($dead))" @{ routing_key = 'article.index.dead'; arguments = @{} } | Out-Null
}
foreach ($exchangeName in @($exchange, $dlx)) {
    $declared = Invoke-IndexManagement GET "exchanges/$encodedVhost/$([uri]::EscapeDataString($exchangeName))"
    if ($declared.type -ne 'direct' -or -not $declared.durable -or $declared.auto_delete -or $declared.internal) {
        throw "Exchange类型或持久性错误：$exchangeName"
    }
    if ($declared.arguments.PSObject.Properties.Name -contains 'alternate-exchange') { throw '索引专用Exchange不能配置备用路由' }
}
foreach ($item in @(@{ Name = $work; Args = $workArguments; Exchange = $exchange; Route = 'article.index.changed' }, @{ Name = $dead; Args = $deadArguments; Exchange = $dlx; Route = 'article.index.dead' })) {
    $encodedName = [uri]::EscapeDataString($item.Name)
    $queue = Invoke-IndexManagement GET "queues/$encodedVhost/$encodedName"
    if ($queue.type -ne 'quorum' -or -not $queue.durable -or $queue.auto_delete) { throw "队列持久性或类型错误：$($item.Name)" }
    foreach ($argument in $item.Args.GetEnumerator()) {
        if ([string]$queue.arguments.($argument.Key) -ne [string]$argument.Value) { throw "队列参数不匹配：$($argument.Key)" }
    }
    foreach ($expiry in @('message-ttl', 'expires')) {
        if ($queue.effective_policy_definition.PSObject.Properties.Name -contains $expiry -or
            $queue.arguments.PSObject.Properties.Name -contains ('x-' + $expiry)) { throw '索引队列不能配置消息TTL或自动过期' }
    }
    # 防止Operator Policy覆盖安全参数，特别是DLQ有限投递次数导致消息被丢弃。
    foreach ($critical in @('overflow', 'dead-letter-strategy', 'delivery-limit', 'dead-letter-exchange', 'dead-letter-routing-key', 'max-length-bytes')) {
        $configured = $queue.effective_policy_definition.($critical)
        if ($null -ne $configured -and [string]$configured -ne [string]$item.Args.('x-' + $critical)) { throw "生效策略与安全参数冲突：$critical" }
    }
    $bindings = Invoke-IndexManagement GET "queues/$encodedVhost/$encodedName/bindings"
    if (-not ($bindings | Where-Object { $_.source -eq $item.Exchange -and $_.routing_key -eq $item.Route })) { throw "队列绑定缺失：$($item.Name)" }
}
# mandatory仅证明至少路由到一个队列；专用路由必须只绑定预期索引工作队列。
$destinations = @(Invoke-IndexManagement GET "exchanges/$encodedVhost/$([uri]::EscapeDataString($exchange))/bindings/source" |
    Where-Object { $_.routing_key -eq 'article.index.changed' })
if ($destinations.Count -ne 1 -or $destinations[0].destination -ne $work -or $destinations[0].destination_type -ne 'queue') {
    throw '索引事件路由必须唯一指向预期工作队列'
}
Write-Output "索引持久队列、死信安全参数和绑定已核验：$Namespace"
