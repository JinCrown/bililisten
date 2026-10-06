param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$ProxyUrl,
    [ValidateSet('Debug', 'Release', 'InternalQa', 'Beta', 'Check')][string]$Mode = 'Check',
    [string]$SigningHome = (Join-Path $env:USERPROFILE '.bililisten-signing\beta'),
    [int]$BetaVersionCode = 0,
    [string]$BetaVersionName,
    [switch]$DeviceTests,
    [switch]$ProbeOnly,
    [switch]$Verify,
    [switch]$Offline,
    [switch]$RestartDaemon
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe'))) {
    throw '请通过 -JdkHome 指定 JDK 17 或兼容的 Android Studio jbr 目录。'
}
$oldJava = $env:JAVA_HOME
$oldOptions = $env:JAVA_OPTS
$signingNames = @('BILI_BETA_KEYSTORE', 'BILI_BETA_STORE_PASSWORD', 'BILI_BETA_KEY_ALIAS')
$oldSigning = @{}
foreach ($name in $signingNames) { $oldSigning[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$arguments = @('--console=plain')
if ($Offline) { $arguments += '--offline' }
try {
    $env:JAVA_HOME = $JdkHome
    if ($ProbeOnly -and ($Mode -ne 'Beta' -or -not $DeviceTests)) { throw '-ProbeOnly requires -Mode Beta -DeviceTests.' }
    if ($Mode -eq 'Beta') {
        . (Join-Path $PSScriptRoot 'beta-signing.ps1') -SigningHome $SigningHome
        $signing = Get-BetaSigning $SigningHome
        $env:BILI_BETA_KEYSTORE = $signing.Store
        $env:BILI_BETA_STORE_PASSWORD = $signing.Password
        $env:BILI_BETA_KEY_ALIAS = $signing.Alias
        if ($BetaVersionCode -lt 0 -or ($BetaVersionName -and $BetaVersionName -notmatch '^[A-Za-z0-9.-]+$')) { throw 'Invalid beta version.' }
        $arguments += @('--no-daemon', '--no-parallel', '--max-workers=2', '-Pkotlin.incremental=false', '-Pkotlin.compiler.execution.strategy=in-process')
        if ($BetaVersionCode -gt 0) { $arguments += "-PbetaVersionCode=$BetaVersionCode" }
        if ($BetaVersionName) { $arguments += "-PbetaVersionName=$BetaVersionName" }
        if ($DeviceTests) { $arguments += @('-PdeviceTestBuildType=betaProbe', ':app:assembleBetaProbeAndroidTest', ':app:assembleBetaProbe') }
        if ($Verify) { $arguments += @('checkArchitecture', ':shared:testDebugUnitTest', ':app:testBetaUnitTest', ':app:lintBeta') }
    } elseif ($DeviceTests) { throw '-DeviceTests requires -Mode Beta.' }
    if ($ProxyUrl) {
        $proxy = [uri]$ProxyUrl
        if ($proxy.Scheme -ne 'http' -or $proxy.UserInfo) { throw '这里只支持不含凭据的 HTTP 构建代理。' }
        $proxyArgs = @("-Dhttp.proxyHost=$($proxy.Host)", "-Dhttp.proxyPort=$($proxy.Port)", "-Dhttps.proxyHost=$($proxy.Host)", "-Dhttps.proxyPort=$($proxy.Port)")
        $env:JAVA_OPTS = ($oldOptions, ($proxyArgs -join ' ') -join ' ').Trim()
        $arguments += $proxyArgs
    }
    $arguments += switch ($Mode) {
        'Debug' { ':app:assembleDebug' }
        'Release' { ':app:assembleRelease' }
        'InternalQa' { ':app:assembleInternalQa' }
        'Beta' { if ($ProbeOnly) { ':app:assembleBetaProbe' } else { ':app:assembleBeta' } }
        'Check' { 'checkArchitecture'; ':shared:testDebugUnitTest'; ':app:testDebugUnitTest'; ':app:lintDebug'; ':app:assembleDebug' }
    }
    Push-Location -LiteralPath $projectRoot
    try {
        if ($Mode -eq 'Check' -or $Verify) { & (Join-Path $PSScriptRoot 'audit-api.ps1') -Root $projectRoot }
        if ($RestartDaemon) {
            & '.\gradlew.bat' --stop
            if ($LASTEXITCODE -ne 0) { throw '无法正常停止 Gradle daemon。' }
        }
        & '.\gradlew.bat' @arguments
        if ($LASTEXITCODE -ne 0) { throw "Gradle 失败，退出码 $LASTEXITCODE" }
        if ($Mode -eq 'Beta') {
            $sdk = (Get-Content -LiteralPath 'local.properties' | Where-Object { $_ -match '^sdk.dir=' }) -replace '^sdk.dir=', '' -replace '\\\\', '\' -replace '\\:', ':'
            $signer = Join-Path $sdk 'build-tools\36.1.0\lib\apksigner.jar'
            $variant = if ($ProbeOnly) { 'betaProbe' } else { 'beta' }
            $apk = Join-Path $projectRoot "app\build\outputs\apk\$variant\app-$variant.apk"
            $proof = & (Join-Path $JdkHome 'bin\java.exe') -jar $signer verify --verbose --print-certs $apk
            if ($LASTEXITCODE -ne 0 -or ($proof -join "`n") -notmatch [regex]::Escape($signing.Certificate.ToLowerInvariant())) { throw 'Beta certificate does not match the pinned dedicated key.' }
            Write-Output 'Beta APK signature verified against the pinned dedicated certificate.'
        }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJava
    $env:JAVA_OPTS = $oldOptions
    foreach ($name in $signingNames) { [Environment]::SetEnvironmentVariable($name, $oldSigning[$name], 'Process') }
    $signing = $null
}
