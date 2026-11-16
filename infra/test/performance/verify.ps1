#requires -Version 7.0
param([Parameter(Mandatory)][string]$RunPath, [switch]$AllowPartial)
# 从逐请求CSV独立重算最近秩分位数，校验样本完整性；不以聚合JSON自证正确。
$ErrorActionPreference = 'Stop'
$resolved = (Resolve-Path -LiteralPath $RunPath).Path
$metadata = Get-Content -LiteralPath (Join-Path $resolved 'metadata.json') -Raw | ConvertFrom-Json
if ($metadata.status -ne 'measured' -and -not $AllowPartial) { throw '整次运行未完成；检查已有阶段时需显式使用-AllowPartial' }
$checks = [Collections.Generic.List[object]]::new()
$missing = [Collections.Generic.List[string]]::new()
function Test-Samples($Stage, [string]$Path, [bool]$Core) {
    $samples = @(Import-Csv -LiteralPath $Path)
    $values = @($samples | ForEach-Object { [long]$_.latencyNanos } | Sort-Object)
    if ($values.Count -ne $Stage.completed -or $values.Count -eq 0) { throw "样本数量不一致：$Path" }
    foreach ($percentile in @(50, 95, 99)) {
        $expected = $values[[int][Math]::Ceiling($values.Count * $percentile / 100.0) - 1] / 1000000.0
        $actual = [double]$Stage."p$($percentile)Ms"
        if ([Math]::Abs($expected - $actual) -gt 0.000001) { throw "P$percentile 不一致：$Path" }
    }
    $statuses = ($Stage.statusCounts.PSObject.Properties.Value | Measure-Object -Sum).Sum
    if ($statuses -ne $values.Count) { throw "状态分类数量不一致：$Path" }
    if ($Core) {
        if ($Stage.planned -ne $Stage.completed + $Stage.dropped) { throw "计划/完成/丢弃数量不一致：$Path" }
        if (@($samples | Where-Object { [int]$_.status -eq 200 }).Count -ne $Stage.success) { throw "成功状态数量不一致：$Path" }
    }
    $checks.Add([pscustomobject]@{ kind = 'http'; file = [IO.Path]::GetRelativePath($resolved, $Path); samples = $values.Count; percentilesMatch = $true })
}
foreach ($round in Get-ChildItem -LiteralPath $resolved -Directory -Filter 'round-*' | Sort-Object Name) {
    $corePath = Join-Path $round.FullName 'core.json'
    if ($metadata.suite -in @('All','Core') -and -not (Test-Path -LiteralPath $corePath)) {
        $missing.Add("$($round.Name)/core.json")
        if (-not $AllowPartial) { throw "缺少Core报告：$round" }
    }
    if ($metadata.suite -in @('All','Core') -and (Test-Path -LiteralPath $corePath)) {
        $core = Get-Content -LiteralPath $corePath -Raw | ConvertFrom-Json
        if ($core.passed -ne $true) { throw "Core未通过：$round" }
        foreach ($stage in $core.stages) {
            Test-Samples $stage (Join-Path $round.FullName ($stage.scenario + '.csv')) $true
            if ($stage.scenario -like 'pv_*' -and ($stage.sqlCountMatchesSuccess -ne $true -or $stage.persistedIncrement -ne $stage.success)) {
                throw "PV对账未通过：$round"
            }
            if ($stage.scenario -eq 'index_publish_http') {
                $observedPath = Join-Path $round.FullName 'index-observed.csv'
                $observed = @(Import-Csv -LiteralPath $observedPath | ForEach-Object { [long]$_.observedLatencyNanos } | Sort-Object)
                if ($observed.Count -ne $stage.observedIndexCompletions -or $observed.Count -ne $stage.success) { throw "索引观察数量不一致：$round" }
                foreach ($percentile in @(50, 95, 99)) {
                    $expected = $observed[[int][Math]::Ceiling($observed.Count * $percentile / 100.0) - 1] / 1000000.0
                    if ([Math]::Abs($expected - [double]$stage."indexObservedP$($percentile)Ms") -gt 0.000001) { throw "索引观察P$percentile 不一致：$round" }
                }
                $checks.Add([pscustomobject]@{ kind = 'index_observed'; file = [IO.Path]::GetRelativePath($resolved, $observedPath); samples = $observed.Count; percentilesMatch = $true })
            }
        }
    }
    $likePath = Join-Path $round.FullName 'like-capacity.json'
    if ($metadata.suite -in @('All','Like') -and -not (Test-Path -LiteralPath $likePath)) {
        $missing.Add("$($round.Name)/like-capacity.json")
        if (-not $AllowPartial) { throw "缺少点赞报告：$round" }
    }
    if ($metadata.suite -in @('All','Like') -and (Test-Path -LiteralPath $likePath)) {
        $like = Get-Content -LiteralPath $likePath -Raw | ConvertFrom-Json
        if ($like.sqlAuditConsistent -ne $true) { throw "点赞对账未通过：$round" }
        foreach ($stage in $like.measurements | Where-Object targetQps) {
            Test-Samples $stage (Join-Path $round.FullName ("like-$($stage.scenario)-$($stage.targetQps).csv")) $false
        }
    }
}
if (@($metadata.results).Count -ne $metadata.rounds -and -not $AllowPartial) { throw '实际完成轮数与配置不一致' }
$verification = [ordered]@{
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o'); passed = $true
    runComplete = ($metadata.status -eq 'measured' -and $missing.Count -eq 0)
    missingReports = $missing
    checkedFiles = $checks.Count
    httpSamples = (($checks | Where-Object kind -EQ http).samples | Measure-Object -Sum).Sum
    indexObservedSamples = (($checks | Where-Object kind -EQ index_observed).samples | Measure-Object -Sum).Sum
    checks = $checks
}
$verification | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $resolved 'verification.json') -Encoding utf8
Write-Output "原始样本复核通过：$($verification.checkedFiles)个CSV，$($verification.httpSamples)个HTTP样本，$($verification.indexObservedSamples)个异步观察样本。"
