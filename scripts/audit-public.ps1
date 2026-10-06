param([string]$Root = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$gitFiles = @(& git -C $rootPath -c core.quotepath=false ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Public audit requires a Git working tree.' }
$files = @($gitFiles | Sort-Object -Unique)
if ($files.Count -eq 0) { throw 'No public files to audit.' }

$blockedPaths = @(
    '(^|/)(\.local|\.gradle|\.kotlin|build|artifacts|captures|__pycache__)/',
    '^(design|ui-preview)/',
    '^docs/(?!public/|api-catalog\.json$)',
    '(^|/)(local\.properties|signing\.json|\.env(?:\..*)?)$',
    '\.(jks|keystore|p12|dpapi|pem|key|db|sqlite|apk|aab|hprof|log|pyc)$'
)
$secretPatterns = @(
    '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----',
    '\bgh[pousr]_[A-Za-z0-9]{30,}\b',
    '\bgithub_pat_[A-Za-z0-9_]{40,}\b',
    '\bAKIA[0-9A-Z]{16}\b',
    '\bAIza[0-9A-Za-z_-]{35}\b',
    '(?i)(?:SESSDATA|bili_jct)\s*[:=]\s*["'']?[A-Za-z0-9%+._/-]{24,}',
    '(?i)(?:storePassword|keyPassword|BILI_BETA_STORE_PASSWORD)\s*=\s*["''][^"''\r\n]{8,}["'']',
    '(?i)[A-Z]:[\\/](?:Users[\\/](?!Public[\\/])[^\\/\r\n"'']+|Android Studio)'
)
$binary = '\.(png|jpg|jpeg|webp|gif|ico|jar|dll|wav|mp3)$'
$failures = [System.Collections.Generic.List[string]]::new()
foreach ($relative in $files) {
    foreach ($pattern in $blockedPaths) {
        if ($relative -match $pattern) { $failures.Add("Private/generated path: $relative"); break }
    }
    $path = Join-Path $rootPath $relative
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    if ($relative -match $binary) { continue }
    $content = [IO.File]::ReadAllText($path)
    # These exact, deliberately invalid literals belong to session rejection tests.
    $content = $content.Replace('SESSDATA=invalid-account-login-fixture', 'SESSDATA=fixture')
    $content = $content.Replace('SESSDATA=m0-invalid-local-fixture', 'SESSDATA=fixture')
    foreach ($pattern in $secretPatterns) {
        if ([regex]::IsMatch($content, $pattern)) {
            # Never echo a matched credential, even on audit failure.
            $failures.Add("Possible credential or personal path: $relative")
            break
        }
    }
}
if ($failures.Count -gt 0) { throw ($failures -join "`n") }
Write-Output "Public candidate audit passed: $($files.Count) files. Images and third-party rights still require manual review."
