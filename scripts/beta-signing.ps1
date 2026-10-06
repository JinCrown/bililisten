param(
    [switch]$Initialize,
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$SigningHome = (Join-Path $env:USERPROFILE '.bililisten-signing\beta')
)
$ErrorActionPreference = 'Stop'

function Get-BetaSigning([string]$Directory) {
    $config = Get-Content -LiteralPath (Join-Path $Directory 'signing.json') -Raw | ConvertFrom-Json
    $store = Join-Path $Directory 'beta.p12'
    if ((Get-FileHash -LiteralPath $store -Algorithm SHA256).Hash -ne $config.keystoreSha256) {
        throw 'Beta keystore changed. Restore the saved key; do not generate a replacement.'
    }
    $secure = (Get-Content -LiteralPath (Join-Path $Directory 'password.dpapi') -Raw).Trim() | ConvertTo-SecureString
    $credential = [pscredential]::new($config.alias, $secure)
    return @{ Store = $store; Alias = $config.alias; Password = $credential.GetNetworkCredential().Password; Certificate = $config.certificateSha256 }
}

if ($Initialize) {
    if (Test-Path -LiteralPath $SigningHome) {
        throw 'Signing directory already exists. Initialization never replaces an existing key.'
    }
    $keytool = Join-Path $JdkHome 'bin\keytool.exe'
    if (-not (Test-Path -LiteralPath $keytool)) { throw 'Specify a compatible JDK.' }
    New-Item -ItemType Directory -Path $SigningHome | Out-Null
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $SigningHome
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($identity in @($sid, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    }
    Set-Acl -LiteralPath $SigningHome -AclObject $acl
    $bytes = [byte[]]::new(48)
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes)
    $oldPassword = $env:BILI_BETA_STORE_PASSWORD
    try {
        $env:BILI_BETA_STORE_PASSWORD = $password
        $store = Join-Path $SigningHome 'beta.p12'
        & $keytool -genkeypair -keystore $store -storetype PKCS12 -alias bililisten-beta -keyalg RSA -keysize 3072 -validity 36500 -dname 'CN=BiliListen Beta, OU=Internal Testing, O=BiliListen' -storepass:env BILI_BETA_STORE_PASSWORD -keypass:env BILI_BETA_STORE_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key creation failed. Inspect the private directory before recovery.' }
        $password | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $SigningHome 'password.dpapi') -Encoding ascii
        $cert = Join-Path $SigningHome 'certificate.der'
        & $keytool -exportcert -keystore $store -alias bililisten-beta -storepass:env BILI_BETA_STORE_PASSWORD -file $cert
        if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed.' }
        @{ alias = 'bililisten-beta'; keystoreSha256 = (Get-FileHash $store -Algorithm SHA256).Hash; certificateSha256 = (Get-FileHash $cert -Algorithm SHA256).Hash } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $SigningHome 'signing.json') -Encoding ascii
        $backup = "$SigningHome-backup"
        if (Test-Path -LiteralPath $backup) { throw 'Backup already exists; it will not be replaced.' }
        New-Item -ItemType Directory -Path $backup | Out-Null
        Set-Acl -LiteralPath $backup -AclObject $acl
        foreach ($name in @('beta.p12', 'password.dpapi', 'signing.json', 'certificate.der')) {
            Copy-Item -LiteralPath (Join-Path $SigningHome $name) -Destination (Join-Path $backup $name)
        }
        if ((Get-FileHash (Join-Path $backup 'beta.p12')).Hash -ne (Get-FileHash $store).Hash) { throw 'Backup verification failed.' }
        Write-Output 'Dedicated beta key created; restricted-access backup verified. No plaintext password was saved.'
    } finally {
        $env:BILI_BETA_STORE_PASSWORD = $oldPassword
        $password = $null
    }
}
