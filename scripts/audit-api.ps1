param([string]$Root = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$catalog = Get-Content -Raw -LiteralPath (Join-Path $Root 'docs/api-catalog.json') | ConvertFrom-Json
$known = @($catalog.groups | ForEach-Object { $_.paths })
$dynamic = @($catalog.dynamicSourcePatterns.PSObject.Properties.Name)
foreach ($pattern in $catalog.dynamicSourcePatterns.PSObject.Properties) {
    foreach ($path in $pattern.Value) { if ($path -notin $known) { throw "Uncatalogued expansion: $path" } }
}
$files = @(Get-ChildItem -LiteralPath (Join-Path $Root 'shared/src/commonMain') -Recurse -Filter '*.kt') +
    @(Get-ChildItem -LiteralPath (Join-Path $Root 'app/src/main') -Recurse -Filter '*.kt')
$found = @{}
foreach ($file in $files) {
    $source = Get-Content -Raw -LiteralPath $file.FullName
    foreach ($path in $catalog.retiredPaths) {
        if ($source.Contains('"' + $path + '"') -or $source.Contains($path + '?') -or $source.Contains($path + '"')) {
            throw "Retired API found in production: $path ($($file.Name))"
        }
    }
    foreach ($match in [regex]::Matches($source, '(?:https://[a-z0-9.-]+)?(/(?:x/|xlive/|session_svr/|svr_sync/|main/)[^"\s]+)"')) {
        $path = $match.Groups[1].Value
        if ($path -notin $known -and $path -notin $dynamic) { throw "Unreviewed API path: $path ($($file.Name))" }
        $found[$path] = $true
    }
}
if ($known.Count -ne @($known | Sort-Object -Unique).Count) { throw 'Duplicate API catalog entries.' }
foreach ($path in $known) {
    $expansion = @($catalog.dynamicSourcePatterns.PSObject.Properties | Where-Object { $path -in $_.Value -and $found.ContainsKey($_.Name) })
    if (-not $found.ContainsKey($path) -and $expansion.Count -eq 0) { throw "Catalog endpoint absent from source: $path" }
}
Write-Output "API catalog coverage passed: $($known.Count) expanded endpoints; retired paths absent. This is a source gate, not live API acceptance."
