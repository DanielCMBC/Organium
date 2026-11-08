# Runs Organium with JVM settings measured on Melcer Chamber Music Hall (8 GB machine, 2.2 GB of samples;
# research/live-performance-report.md):
#   -XX:+UseZGC  concurrent collector: pauses stay near 1 ms in total, so they never stall the ASIO
#                callback (it runs Java code). G1's 20-45 ms young-collection pauses caused dropouts.
#   -Xmx4g       heap limit; resident memory stays near what the samples need (about 2.9 GB peak)
# Examples:
#   .\run.ps1 --organ="D:\OrganiumSampleSets\OrganDefinitions\Melcer Chamber Music Hall.Organ_Hauptwerk_xml" --asio
#   .\run.ps1 --organ=<file> --demo
java -Xmx4g -XX:+UseZGC --enable-native-access=ALL-UNNAMED -cp "$PSScriptRoot\out" organium.App @args
exit $LASTEXITCODE
