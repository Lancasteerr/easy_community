#requires -Version 7.0
param([Parameter(Mandatory)][string]$RunPath)
# 汇总全部轮次，不挑选最优结果；原始JSON和CSV始终保留。
$ErrorActionPreference = 'Stop'
$resolved = (Resolve-Path -LiteralPath $RunPath).Path
$metadata = Get-Content -LiteralPath (Join-Path $resolved 'metadata.json') -Raw | ConvertFrom-Json
$rows = @()
$pairs = @()
foreach ($round in Get-ChildItem -LiteralPath $resolved -Directory -Filter 'round-*' | Sort-Object Name) {
    $corePath = Join-Path $round.FullName 'core.json'
    if (Test-Path -LiteralPath $corePath) {
        $core = Get-Content -LiteralPath $corePath -Raw | ConvertFrom-Json
        foreach ($stage in $core.stages) {
            $rows += [pscustomobject]@{
                round = $round.Name; scenario = $stage.scenario; targetQps = $stage.targetQps
                actualQps = $stage.actualQps; p50Ms = $stage.p50Ms; p95Ms = $stage.p95Ms; p99Ms = $stage.p99Ms
                completed = $stage.completed; success = $stage.success; dropped = $stage.dropped
                extra = if ($stage.scenario -eq 'pv_redis') { "writes=$($stage.pvWriteStatements); audit=$($stage.sqlCountMatchesSuccess)" }
                    elseif ($stage.scenario -eq 'pv_sql') { "writes=$($stage.pvWriteStatements); audit=$($stage.sqlCountMatchesSuccess)" }
                    elseif ($stage.scenario -eq 'index_publish_http') { "ES observed P95=$($stage.indexObservedP95Ms); P99=$($stage.indexObservedP99Ms); count=$($stage.observedIndexCompletions)" }
                    else { '' }
            }
        }
        foreach ($pair in @(@('cache_source','cache_cached'), @('pv_sql','pv_redis'))) {
            $baseline = $core.stages | Where-Object scenario -EQ $pair[0]
            $optimized = $core.stages | Where-Object scenario -EQ $pair[1]
            if ($baseline -and $optimized) {
                $pairs += [pscustomobject]@{
                    round = $round.Name; comparison = "$($pair[0])->$($pair[1])"
                    p95ReductionPercent = 100 * (1 - $optimized.p95Ms / $baseline.p95Ms)
                    p99ReductionPercent = 100 * (1 - $optimized.p99Ms / $baseline.p99Ms)
                    # 发压丢弃可能使两组完成数略异，按每成功请求的写语句率归一化。
                    writeReductionPercent = if ($pair[0] -eq 'pv_sql' -and $baseline.success -gt 0 -and $optimized.success -gt 0) {
                        100 * (1 - ($optimized.pvWriteStatements / $optimized.success) / ($baseline.pvWriteStatements / $baseline.success))
                    } else { $null }
                }
            }
        }
    }
    $likePath = Join-Path $round.FullName 'like-capacity.json'
    if (Test-Path -LiteralPath $likePath) {
        $like = Get-Content -LiteralPath $likePath -Raw | ConvertFrom-Json
        foreach ($stage in $like.measurements | Where-Object targetQps) {
            $rows += [pscustomobject]@{
                round = $round.Name; scenario = "like_$($stage.scenario)"; targetQps = $stage.targetQps
                actualQps = $stage.actualQps; p50Ms = $stage.p50Ms; p95Ms = $stage.p95Ms; p99Ms = $stage.p99Ms
                completed = $stage.completed; success = $stage.success; dropped = $stage.skippedBusyClient
                extra = "pending=$($stage.acceptedPending); SQL audit=$($like.sqlAuditConsistent)"
            }
        }
    }
}
$rows | Export-Csv -LiteralPath (Join-Path $resolved 'summary.csv') -NoTypeInformation -Encoding utf8
ConvertTo-Json -InputObject @($pairs) -Depth 5 | Set-Content -LiteralPath (Join-Path $resolved 'paired-comparisons.json') -Encoding utf8
$lines = [Collections.Generic.List[string]]::new()
$lines.Add('# 性能实测汇总')
$lines.Add('')
$lines.Add("运行状态：$($metadata.status)；时长：$($metadata.seconds)秒/阶段；轮数：$($metadata.rounds)。延迟单位ms。")
$lines.Add('')
$lines.Add('缓存/PV是测试专用HTTP对照；feed/文章发布/点赞使用实际接口。全部为本机共享硬件，不代表生产极限。负降幅代表变慢。')
$lines.Add('')
$lines.Add('| 轮次 | 场景 | 目标QPS | 实际QPS | P50 | P95 | P99 | 成功/完成 | 发压丢弃 | 附加结果 |')
$lines.Add('|---|---|---:|---:|---:|---:|---:|---:|---:|---|')
foreach ($row in $rows) {
    $lines.Add(('| {0} | {1} | {2} | {3:F2} | {4:F2} | {5:F2} | {6:F2} | {7}/{8} | {9} | {10} |' -f
        $row.round, $row.scenario, $row.targetQps, $row.actualQps, $row.p50Ms, $row.p95Ms, $row.p99Ms,
        $row.success, $row.completed, $row.dropped, $row.extra))
}
$lines.Add('')
$lines.Add('对照逐轮降幅保存在 paired-comparisons.json；不得把冷热/绕过缓存对照称为历史版本优化。')
$lines | Set-Content -LiteralPath (Join-Path $resolved 'summary.md') -Encoding utf8
Write-Output "汇总已保存：$(Join-Path $resolved 'summary.md')"
