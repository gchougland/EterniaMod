$ErrorActionPreference = 'Stop'
$eterniaRoot = Split-Path $PSScriptRoot -Parent
$eterniaPrefabRoot = Join-Path $eterniaRoot 'src/main/resources/Server/Prefabs'
$eterniaBlocks = [System.Collections.Generic.List[object]]::new()
for ($x = -12; $x -le 12; $x++) {
  for ($z = -14; $z -le 14; $z++) {
    for ($y = 0; $y -le 12; $y++) {
      $block = 'Empty'
      $edge = [Math]::Abs($x) -eq 11 -or [Math]::Abs($z) -eq 13
      $inside = [Math]::Abs($x) -le 11 -and [Math]::Abs($z) -le 13
      $roofY = 12 - [int][Math]::Floor([Math]::Abs($x) / 2)
      if ($y -eq 0) { $block = 'Rock_Stone_Brick' }
      elseif ($inside -and $edge -and $y -le 6) {
        $block = 'Wood_Softwood_Planks'
        if ($x % 5 -eq 0 -or $z % 5 -eq 0) { $block = 'Wood_Beech_Trunk' }
        if ($y -in 3,4 -and (($z % 5 -eq 2 -and [Math]::Abs($x) -eq 11) -or ($x % 5 -eq 2 -and [Math]::Abs($z) -eq 13))) { $block = 'Empty' }
        if ($z -eq -13 -and [Math]::Abs($x) -le 1 -and $y -le 4) { $block = 'Empty' }
      }
      if ($y -eq $roofY) { $block = 'Wood_Softwood_Roof_Flat' }
      if ($x -eq 1 -and $z -eq -9 -and $y -eq 1) { $block = 'Eternia_Management_Block' }
      if ($y -eq 1 -and [Math]::Abs($x) -eq 5 -and [Math]::Abs($z) -le 7) { $block = 'Wood_Softwood_Planks' }
      $eterniaBlocks.Add([ordered]@{x=$x;y=$y;z=$z;name=$block})
    }
  }
}
$eterniaPrefab = [ordered]@{version=8;blockIdVersion=11;anchorX=0;anchorY=0;anchorZ=0;blocks=$eterniaBlocks}
$eterniaPrefab | ConvertTo-Json -Depth 10 -Compress | Set-Content -LiteralPath (Join-Path $eterniaPrefabRoot 'GuildHall.prefab.json') -Encoding utf8NoBOM
$eterniaPorch = [System.Collections.Generic.List[object]]::new()
for ($x=0;$x -lt 5;$x++) { for ($z=0;$z -lt 3;$z++) { $eterniaPorch.Add([ordered]@{x=$x;y=0;z=$z;name='Wood_Softwood_Planks'}) } }
foreach ($x in 0,4) { for ($y=1;$y -lt 3;$y++) { $eterniaPorch.Add([ordered]@{x=$x;y=$y;z=2;name='Wood_Beech_Trunk'}) } }
[ordered]@{version=8;blockIdVersion=11;anchorX=0;anchorY=0;anchorZ=0;blocks=$eterniaPorch} | ConvertTo-Json -Depth 10 -Compress | Set-Content -LiteralPath (Join-Path $eterniaPrefabRoot 'StarterPorch.prefab.json') -Encoding utf8NoBOM
Write-Output 'Authored GuildHall and StarterPorch prefabs.'
