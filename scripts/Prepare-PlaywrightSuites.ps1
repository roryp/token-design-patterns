$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'playwright-patterns.mjs') -Raw
if (-not $source.StartsWith('export async function testPatterns(')) {
    throw 'The shared browser suite must export the testPatterns function.'
}

# MCP evaluates a self-contained function; ES module imports are not available in every server.
$function = $source.Substring('export '.Length)
$output = Join-Path (Split-Path $PSScriptRoot -Parent) '.playwright-mcp'
New-Item -ItemType Directory -Path $output -Force | Out-Null
foreach ($suite in @('desktop', 'mobile', 'branches', 'inputs')) {
    $wrapper = "async (page) => {`n$function`nreturn testPatterns(page, '$suite');`n}`n"
    $path = Join-Path $output "patterns-$suite.js"
    [System.IO.File]::WriteAllText($path, $wrapper)
    Write-Output $path
}
