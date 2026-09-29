param(
    [ValidateSet('Setup','Start','Stop','Status','Test','Game','Web','Backup')]
    [string]$Action = 'Status'
)
$ErrorActionPreference = 'Stop'
$etRoot = Split-Path -Parent $PSScriptRoot
$etPgRoot = Join-Path $etRoot '.local/postgres'
$etPgData = Join-Path $etPgRoot 'data'
$etPgBin = Join-Path $etPgRoot 'pgsql/bin'
$etSecretsFile = Join-Path $etPgRoot 'credentials.json'
$etSavedEnvironment = @{}

function Set-EtEnvironment([string]$Name, [string]$Value) {
    if (-not $etSavedEnvironment.ContainsKey($Name)) { $etSavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function New-EtSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}
function Read-EtConfig {
    if (-not (Test-Path -LiteralPath $etSecretsFile)) { throw 'Run scripts/postgres-local.ps1 Setup first.' }
    $config = Get-Content -LiteralPath $etSecretsFile -Raw | ConvertFrom-Json
    if ($config.version -ne 1 -or $config.port -ne 55432) { throw 'Unexpected local PostgreSQL configuration.' }
    return $config
}
function Invoke-EtSql([object]$Config, [string]$Sql) {
    Set-EtEnvironment 'PGPASSWORD' $Config.admin.password
    $Sql | & (Join-Path $etPgBin 'psql.exe') -X -h 127.0.0.1 -p $Config.port -U $Config.admin.user -d postgres -v ON_ERROR_STOP=1 -At
    if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL command failed.' }
}
function Start-EtDatabase([object]$Config) {
    & (Join-Path $etPgBin 'pg_ctl.exe') status -D $etPgData *> $null
    if ($LASTEXITCODE -eq 0) { return }
    $out = Join-Path $etPgRoot 'start.stdout.log'
    $err = Join-Path $etPgRoot 'start.stderr.log'
    $pgArgs = @('start', '-D', ('"' + $etPgData + '"'), '-l', ('"' + (Join-Path $etPgRoot 'server.log') + '"'), '-w', '-t', '30')
    # Start-Process -Wait waits for the entire descendant tree, including postgres.
    # Wait only for pg_ctl, which reports readiness and then exits.
    $process = Start-Process -FilePath (Join-Path $etPgBin 'pg_ctl.exe') -ArgumentList $pgArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    if (-not $process.WaitForExit(40000)) { throw 'Timed out waiting for pg_ctl; inspect the local PostgreSQL logs.' }
    if ($process.ExitCode -ne 0) { throw "PostgreSQL did not start. Read $err and the local server.log; another service may occupy port 55432." }
}
function Get-EtNode {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if ($node -and [int]((& $node.Source --version).TrimStart('v').Split('.')[0]) -ge 22) { return $node.Source }
    $bundled = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
    if (Test-Path -LiteralPath $bundled) { return $bundled }
    throw 'Node 22+ is required. Install it or make the bundled Node runtime available.'
}
function Set-EtGameEnvironment([object]$Config) {
    Set-EtEnvironment 'ETERNIA_MODE' 'local'
    Set-EtEnvironment 'ETERNIA_DATABASE_URL' "jdbc:postgresql://127.0.0.1:$($Config.port)/eternia_game_dev"
    Set-EtEnvironment 'ETERNIA_DATABASE_USER' $Config.gameDev.user
    Set-EtEnvironment 'ETERNIA_DATABASE_PASSWORD' $Config.gameDev.password
    Set-EtEnvironment 'ETERNIA_BRIDGE_ADDRESS' '127.0.0.1'
    Set-EtEnvironment 'ETERNIA_BRIDGE_PORT' '9011'
    Set-EtEnvironment 'ETERNIA_BRIDGE_TOKEN' $Config.bridgeSecret
    Set-EtEnvironment 'ETERNIA_WEBSITE_URL' 'http://127.0.0.1:3848'
}
function Set-EtWebEnvironment([object]$Config) {
    Set-EtEnvironment 'NODE_ENV' 'development'
    Set-EtEnvironment 'RAILWAY_ENVIRONMENT' ''
    Set-EtEnvironment 'LOCAL_FIXTURES' 'false'
    Set-EtEnvironment 'DATABASE_URL' "postgresql://$($Config.webDev.user):$($Config.webDev.password)@127.0.0.1:$($Config.port)/eternia_web_dev"
    Set-EtEnvironment 'SESSION_SECRET' $Config.sessionSecret
    Set-EtEnvironment 'PUBLIC_BASE_URL' 'http://127.0.0.1:3848'
    Set-EtEnvironment 'PORT' '3848'
    Set-EtEnvironment 'DATA_DIR' '../.local/postgres/web-data'
    Set-EtEnvironment 'GAME_BRIDGE_URL' 'http://127.0.0.1:9011'
    Set-EtEnvironment 'GAME_BRIDGE_TOKEN' $Config.bridgeSecret
    # Keep OAuth explicitly unconfigured unless the operator supplies Eternia's own client.
    Set-EtEnvironment 'HYTALE_OIDC_REDIRECT_URI' 'http://127.0.0.1:3848/auth/callback'
}

Push-Location -LiteralPath $etRoot
try {
    if ($Action -eq 'Setup') {
        New-Item -ItemType Directory -Force -Path $etPgRoot | Out-Null
        $installMarker = Join-Path $etPgRoot 'installation-complete.txt'
        if (-not (Test-Path -LiteralPath $installMarker)) {
            $archive = Join-Path $etPgRoot 'downloads/postgresql-18.6-3-windows-x64-binaries.zip'
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $archive) | Out-Null
            if (-not (Test-Path -LiteralPath $archive)) {
                Write-Host 'Downloading PostgreSQL 18.6 Windows binaries from EDB (about 344 MB)...'
                Invoke-WebRequest -Uri 'https://get.enterprisedb.com/postgresql/postgresql-18.6-3-windows-x64-binaries.zip' -OutFile $archive -UseBasicParsing
            }
            # Pin the archive fetched from EDB's official Windows download link on 2026-09-13.
            $expected = '59F8CE701C63C2ED623C665A5E51B3EF6F2E37CCF837B68FFEED0742D0AE6ABD'
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw 'PostgreSQL archive checksum differs from the pinned download.' }
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $zip = [IO.Compression.ZipFile]::OpenRead($archive)
            try {
                foreach ($entry in $zip.Entries) {
                    if ($entry.FullName -notmatch '^pgsql/(bin|lib|share)/' -or $entry.FullName.EndsWith('/')) { continue }
                    $destination = [IO.Path]::GetFullPath((Join-Path $etPgRoot $entry.FullName))
                    if (-not $destination.StartsWith($etPgRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive entry escapes the local PostgreSQL directory.' }
                    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
                }
            } finally { $zip.Dispose() }
            Set-Content -LiteralPath $installMarker -Value $expected -Encoding ASCII
        }
        if (-not (Test-Path -LiteralPath $etSecretsFile)) {
            if (Test-Path -LiteralPath $etPgData) { throw 'Existing database directory has no matching credentials. Refusing to reinitialize it.' }
            $config = [ordered]@{version=1;port=55432;admin=@{user='eternia_local_admin';password=(New-EtSecret)};bridgeSecret=(New-EtSecret);sessionSecret=(New-EtSecret)}
            foreach ($pair in @(@('gameDev','eternia_game_dev'),@('gameTest','eternia_game_test'),@('webDev','eternia_web_dev'),@('webTest','eternia_web_test'))) {
                $config[$pair[0]] = @{user=$pair[1];password=(New-EtSecret)}
            }
            $config | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $etSecretsFile -Encoding UTF8
        }
        $config = Read-EtConfig
        if (-not (Test-Path -LiteralPath (Join-Path $etPgData 'PG_VERSION'))) {
            if ((Test-Path -LiteralPath $etPgData) -and @(Get-ChildItem -LiteralPath $etPgData -Force).Count -gt 0) { throw 'Existing nonempty data directory is not an initialized PostgreSQL cluster; inspect it before retrying.' }
            $pwFile = Join-Path $etPgRoot 'init-password.tmp'
            try {
                [IO.File]::WriteAllText($pwFile, $config.admin.password + "`n", [Text.UTF8Encoding]::new($false))
                & (Join-Path $etPgBin 'initdb.exe') -D $etPgData -U $config.admin.user --encoding=UTF8 --locale=C --auth=scram-sha-256 "--pwfile=$pwFile"
                if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed.' }
            } finally { if (Test-Path -LiteralPath $pwFile) { Remove-Item -LiteralPath $pwFile } }
            Add-Content -LiteralPath (Join-Path $etPgData 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = 55432`npassword_encryption = 'scram-sha-256'`ntimezone = 'UTC'`n"
        }
        Start-EtDatabase $config
        foreach ($name in @('gameDev','gameTest','webDev','webTest')) {
            $role = $config.$name
            if (@(Invoke-EtSql $config "SELECT 1 FROM pg_roles WHERE rolname='$($role.user)'").Count -eq 0) {
                Invoke-EtSql $config "CREATE ROLE $($role.user) LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$($role.password)';" | Out-Null
            }
            if (@(Invoke-EtSql $config "SELECT 1 FROM pg_database WHERE datname='$($role.user)'").Count -eq 0) {
                Invoke-EtSql $config "CREATE DATABASE $($role.user) OWNER $($role.user);" | Out-Null
            }
            Invoke-EtSql $config "REVOKE CONNECT, TEMPORARY ON DATABASE $($role.user) FROM PUBLIC; GRANT CONNECT, TEMPORARY ON DATABASE $($role.user) TO $($role.user);" | Out-Null
        }
        Write-Host 'PostgreSQL is ready at 127.0.0.1:55432. Game/web development and test databases have separate credentials.'
        Write-Host 'Credentials and database files stay in ignored .local/postgres. No Windows service was installed.'
    } else {
        $config = Read-EtConfig
        switch ($Action) {
            'Start' { Start-EtDatabase $config; Write-Host 'PostgreSQL is running at 127.0.0.1:55432.' }
            'Stop' {
                & (Join-Path $etPgBin 'pg_ctl.exe') stop -D $etPgData -m fast -w -t 30
                if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not stop cleanly.' }
            }
            'Status' {
                & (Join-Path $etPgBin 'pg_ctl.exe') status -D $etPgData
                if ($LASTEXITCODE -ne 0) { throw 'The local PostgreSQL cluster is stopped. Use Start.' }
                Invoke-EtSql $config "SELECT datname FROM pg_database WHERE datname LIKE 'eternia_%' ORDER BY datname;"
            }
            'Test' {
                Start-EtDatabase $config
                Set-EtEnvironment 'ETERNIA_TEST_DATABASE_ALLOW_WRITES' 'true'
                Set-EtEnvironment 'ETERNIA_TEST_DATABASE_URL' "jdbc:postgresql://127.0.0.1:$($config.port)/eternia_game_test"
                Set-EtEnvironment 'ETERNIA_TEST_DATABASE_USER' $config.gameTest.user
                Set-EtEnvironment 'ETERNIA_TEST_DATABASE_PASSWORD' $config.gameTest.password
                Set-EtEnvironment 'ETERNIA_WEB_TEST_DATABASE_URL' "postgresql://$($config.webTest.user):$($config.webTest.password)@127.0.0.1:$($config.port)/eternia_web_test"
                & (Join-Path $etRoot 'gradlew.bat') --gradle-user-home (Join-Path $env:USERPROFILE '.gradle') test verifyReleaseJar --rerun-tasks --no-daemon --console=plain
                if ($LASTEXITCODE -ne 0) { throw 'Java verification failed.' }
                Push-Location -LiteralPath (Join-Path $etRoot 'website')
                try { & (Get-EtNode) --test; if ($LASTEXITCODE -ne 0) { throw 'Website verification failed.' } }
                finally { Pop-Location }
            }
            'Game' {
                Start-EtDatabase $config
                Set-EtGameEnvironment $config
                Write-Host 'PostgreSQL playtest: connect to 127.0.0.1:5524. This uses a separate run-postgres world.'
                & (Join-Path $etRoot 'gradlew.bat') --gradle-user-home (Join-Path $env:USERPROFILE '.gradle') runServerPostgres --no-daemon --console=plain
                if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL game server failed.' }
            }
            'Web' {
                Start-EtDatabase $config
                Set-EtWebEnvironment $config
                Push-Location -LiteralPath (Join-Path $etRoot 'website')
                try {
                    $node = Get-EtNode
                    & $node scripts/migrate.js
                    if ($LASTEXITCODE -ne 0) { throw 'Website migration failed.' }
                    Write-Host 'PostgreSQL website: http://127.0.0.1:3848. Real sign-in needs your new Eternia OAuth client.'
                    & $node server/index.js
                    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL website failed.' }
                } finally { Pop-Location }
            }
            'Backup' {
                Start-EtDatabase $config
                $backupDir = Join-Path $etPgRoot ('backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
                New-Item -ItemType Directory -Path $backupDir | Out-Null
                foreach ($name in @('gameDev','webDev')) {
                    $role = $config.$name
                    Set-EtEnvironment 'PGPASSWORD' $role.password
                    & (Join-Path $etPgBin 'pg_dump.exe') -h 127.0.0.1 -p $config.port -U $role.user -d $role.user -Fc -f (Join-Path $backupDir ($role.user + '.dump'))
                    if ($LASTEXITCODE -ne 0) { throw 'Database backup failed.' }
                }
                Write-Host "Database backups written to $backupDir. Also preserve the matching world, snapshots and renderer files."
            }
        }
    }
} finally {
    foreach ($name in $etSavedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $etSavedEnvironment[$name], 'Process') }
    Pop-Location
}
