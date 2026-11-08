# Runs Organium with JVM settings measured on Melcer Chamber Music Hall (8 GB machine, 2.2 GB of samples):
#   -XX:G1HeapRegionSize=32m  multi-megabyte sample arrays waste far less heap than with G1's default
#                             2 MB regions (heap in use 2,215 vs 2,725 MiB)
#   -Xmx4g                    heap limit; resident memory stays near what the samples need (2.8 GB peak)
# Examples:
#   .\run.ps1 --organ="D:\OrganiumSampleSets\OrganDefinitions\Melcer Chamber Music Hall.Organ_Hauptwerk_xml" --asio
#   .\run.ps1 --organ=<file> --demo
java -Xmx4g -XX:G1HeapRegionSize=32m --enable-native-access=ALL-UNNAMED -cp "$PSScriptRoot\out" organium.App @args
exit $LASTEXITCODE
