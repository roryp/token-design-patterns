[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [uri] $BaseUrl,

    [ValidateRange(1, 6)]
    [int] $MaxReadAttempts = 6,

    [switch] $AllPatterns
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.AbsoluteUri.TrimEnd('/')
$config = Invoke-RestMethod -Uri "$base/api/config" -TimeoutSec 60
if (-not $config.modelsConfigured) { throw 'The application has no configured provider.' }

function Invoke-Pattern {
    param([string] $Pattern, [string] $InputText, [bool] $CacheEnabled = $false)
    $body = @{ patternId = $Pattern; input = $InputText; cacheEnabled = $CacheEnabled } | ConvertTo-Json
    $result = Invoke-RestMethod -Uri "$base/api/runs" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 180
    if ([string]::IsNullOrWhiteSpace($result.output)) { throw "$Pattern returned no answer." }
    $usage = $result.metrics
    $modelEvents = @($result.trace | Where-Object kind -eq 'model')
    if ($usage.modelCalls -le 0 -or $usage.modelCalls -ne $modelEvents.Count) { throw "$Pattern has inconsistent model-call accounting." }
    if ($usage.inputTokens + $usage.outputTokens -ne $usage.observedTokens) { throw "$Pattern has inconsistent observed usage." }
    $tracedTokens = ($modelEvents | ForEach-Object { $_.inputTokens + $_.outputTokens } | Measure-Object -Sum).Sum
    if ($tracedTokens -ne $usage.observedTokens) { throw "$Pattern has inconsistent trace attribution." }
    foreach ($event in $modelEvents) {
        if ($event.inputTokens -le 0 -or $event.outputTokens -le 0) { throw "$Pattern has an unmeasured model call." }
        if ($null -eq $event.cachedInputTokens -or $null -eq $event.cacheWriteTokens) { throw "$Pattern has missing provider cache telemetry." }
        if ($event.cachedInputTokens + $event.cacheWriteTokens -gt $event.inputTokens) { throw "$Pattern has invalid provider cache subsets." }
        if ($null -ne $event.reasoningTokens -and $event.reasoningTokens -gt $event.outputTokens) { throw "$Pattern has invalid reasoning usage." }
    }
    if ($Pattern -in @('caching', 'batching', 'step-back')) {
        if ($usage.avoidedTokens -ne 0 -or $usage.projectedSavingsPercent -ne 0 -or $usage.projectedBaselineTokens -ne $usage.observedTokens) {
            throw "$Pattern incorrectly claims content-token avoidance."
        }
    }
    return $result
}

function Write-Receipt {
    param([string] $Phase, $Result)
    [pscustomobject]@{
        phase = $Phase
        pattern = $Result.patternId
        status = $Result.metrics.cacheStatus
        modelCalls = $Result.metrics.modelCalls
        input = $Result.metrics.inputTokens
        output = $Result.metrics.outputTokens
        cacheRead = $Result.metrics.cachedInputTokens
        cacheWrite = $Result.metrics.cacheWriteTokens
        reasoning = $Result.metrics.reasoningTokens
        milliseconds = $Result.metrics.durationMs
    } | ConvertTo-Json -Compress
}

$question = 'What is idempotency and why does it matter for retries? Give one payment example.'
$readObserved = $false
for ($attempt = 1; $attempt -le $MaxReadAttempts; $attempt++) {
    $result = Invoke-Pattern -Pattern 'caching' -InputText $question -CacheEnabled $true
    if ($result.output -notmatch '(?i)idempoten' -or $result.output -notmatch '(?i)payment|charge') { throw 'The caching answer failed the idempotency/payment quality check.' }
    if ($result.metrics.modelCalls -ne 1) { throw 'The caching workflow must make one model invocation per run.' }
    Write-Receipt -Phase "enabled-$attempt" -Result $result
    if ($result.metrics.cachedInputTokens -ge 1024) {
        if ($result.metrics.cacheStatus -ne 'hit') { throw 'Provider cache reads were mislabeled.' }
        $readObserved = $true
        break
    }
    if ($attempt -lt $MaxReadAttempts) { Start-Sleep -Seconds 2 }
}
if (-not $readObserved) { throw 'No genuine provider cache read of at least 1,024 tokens was observed.' }

$bypassed = Invoke-Pattern -Pattern 'caching' -InputText $question -CacheEnabled $false
Write-Receipt -Phase 'bypassed' -Result $bypassed
if ($bypassed.metrics.cacheStatus -ne 'bypassed' -or $bypassed.metrics.cachedInputTokens -ne 0 -or $bypassed.metrics.cacheWriteTokens -ne 0) {
    throw 'The explicitly bypassed request unexpectedly used the provider cache.'
}

if ($AllPatterns) {
    $patterns = Invoke-RestMethod -Uri "$base/api/patterns" -TimeoutSec 30
    if ($patterns.Count -ne 8) { throw 'The catalog does not contain all eight patterns.' }
    foreach ($pattern in $patterns | Where-Object id -ne 'caching') {
        $result = Invoke-Pattern -Pattern $pattern.id -InputText $pattern.sampleInput
        if ($result.metrics.cacheStatus -ne 'bypassed') { throw "$($pattern.id) unexpectedly enabled provider caching." }
        if ($pattern.id -eq 'tool-use' -and $result.output -notmatch '13[.,]50') { throw 'The tool-use answer changed the deterministic arithmetic.' }
        if ($pattern.id -eq 'batching' -and ($result.metrics.modelCalls -ne 3 -or $result.metrics.concurrency -ne 3)) { throw 'Batching did not fan out over three independent items.' }
        Write-Receipt -Phase 'workflow' -Result $result
    }
}

Write-Output "Verified provider caching at $base (real calls; no simulated telemetry)."
