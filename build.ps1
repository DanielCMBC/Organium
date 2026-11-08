# Compiles Organium into .\out. Run with:
#   java --enable-native-access=ALL-UNNAMED -cp out organium.App [--asio[=driver]] [sample.wav] [release.wav]
$ErrorActionPreference = 'Stop'
$sources = Get-ChildItem -Path "$PSScriptRoot\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac --release 25 -Xlint:all,-restricted -d "$PSScriptRoot\out" $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Built $($sources.Count) files into out\"
