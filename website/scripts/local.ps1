param([switch]$SyncAssets, [switch]$Smoke, [switch]$NativeSmoke)
$ErrorActionPreference = 'Stop'
$etWebsiteRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $etWebsiteRoot
$etNodePath = (Get-Command node -ErrorAction SilentlyContinue).Source
if ($etNodePath) { $etMajorVersion = [int]((& $etNodePath --version).TrimStart('v').Split('.')[0]) }
if (-not $etNodePath -or $etMajorVersion -lt 22) {
    $etBundledNode = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
    if (Test-Path -LiteralPath $etBundledNode) { $etNodePath = $etBundledNode }
    else { throw 'Install Node 22+ or make the bundled Codex Node runtime available.' }
}
$etNpmPath = (Get-Command npm -ErrorAction Stop).Source
$etNpmCli = Join-Path (Split-Path -Parent $etNpmPath) 'node_modules/npm/bin/npm-cli.js'
if (-not (Test-Path -LiteralPath $etNpmCli)) { throw 'npm CLI not found beside npm. Run npm ci using Node 22+ first.' }
& $etNodePath $etNpmCli ci --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
if (-not (Test-Path -LiteralPath 'dev/local.env')) { Copy-Item -LiteralPath 'dev/local.env.example' -Destination 'dev/local.env' }
& $etNodePath scripts/sync-theme.js
if ($LASTEXITCODE -ne 0) { throw 'Theme generation failed.' }
if ($SyncAssets) { & $etNodePath scripts/sync-hytale-viewer-assets.mjs; if ($LASTEXITCODE -ne 0) { throw 'Asset sync failed.' } }
& $etNodePath --test
if ($LASTEXITCODE -ne 0) { throw 'Local tests failed.' }
if ($Smoke -or $NativeSmoke) {
    Write-Host 'The website must already be running at http://127.0.0.1:3847 for the browser smoke test.'
    if ($NativeSmoke) { & $etNodePath scripts/native-render-smoke.js }
    else { & $etNodePath scripts/browser-smoke.js }
    if ($LASTEXITCODE -ne 0) { throw 'Browser smoke test failed.' }
} else {
    & $etNodePath --env-file=dev/local.env server/index.js
}
