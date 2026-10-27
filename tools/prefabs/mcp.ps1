# Call one Hytale MCP tool from the command line: .\tools\prefabs\mcp.ps1 <tool> <args.json> [<out.png>]
# Uses the MCP's own scripts/call-tool.mjs, so it works without an MCP client.
param([Parameter(Mandatory)][string]$Tool, [Parameter(Mandatory)][string]$ArgsFile, [string]$Out)
$mcp = Join-Path $PSScriptRoot '..\..\..\HytaleMCP'
$argsPath = (Resolve-Path $ArgsFile).Path
Push-Location $mcp
try {
  if ($Out) { node scripts/call-tool.mjs $Tool "@$argsPath" --out $Out 2>&1 | ForEach-Object { "$_" } }
  else { node scripts/call-tool.mjs $Tool "@$argsPath" 2>&1 | ForEach-Object { "$_" } }
} finally { Pop-Location }
