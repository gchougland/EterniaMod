# Build one Hub prefab and finalize it: .\tools\prefabs\build.ps1 Eternia_Tavern
# Runs the build script, then prefab_finalize with the new hash, and prints every problem finalize reports.
param([Parameter(Mandatory)][string]$Name)
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$out = node (Join-Path $PSScriptRoot "$Name.build.mjs") 2>&1 | ForEach-Object { "$_" }
$out | Where-Object { $_ -notmatch '^sha256' }
$hash = ($out | Select-String 'sha256 (\w+)').Matches[0].Groups[1].Value
$args = Join-Path $env:TEMP "finalize-$Name.json"
'{"path":"EterniaMod/src/main/resources/Server/Prefabs/EterniaMod/Hub/' + $Name + '.prefab.json","expectedHash":"' + $hash + '"}' | Set-Content -NoNewline -Encoding ascii $args
$text = (& (Join-Path $PSScriptRoot 'mcp.ps1') prefab_finalize $args) -join "`n"
$json = $text.Substring($text.IndexOf('{')) | ConvertFrom-Json
"finalize: fillers $($json.fillersWritten), support $($json.supportSet), overlaps $($json.overlaps.Count), illegal rotations $($json.illegalRotations.Count), unknown $($json.unknown.Count)"
$json.overlaps | ForEach-Object { "  overlap: $_" }
$json.illegalRotations | ForEach-Object { "  rotation: $_" }
$json.unknown | ForEach-Object { "  unknown: $_" }
$json.problems | ForEach-Object { "  problem: $_" }
