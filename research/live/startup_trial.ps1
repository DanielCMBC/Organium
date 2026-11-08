# One short LiveStress startup trial (foreground), then the startup gap analysis.
#   startup_trial.ps1 <run name> <seconds to play> "<extra LiveStress options>" [-sample]
# -sample also runs sample_process.ps1 (WMI process polling every second), as gc_compare.ps1 does.
param([string]$name, [double]$seconds, [string]$options, [switch]$sample)
Set-Location D:\Organium
$o = "D:\Organium\research\live\$name"
New-Item -ItemType Directory -Force $o | Out-Null
if ($sample) {
  Start-Process powershell -ArgumentList '-NoProfile', '-File', 'D:\Organium\research\live\sample_process.ps1', 'LiveStress', "$o\process-memory.csv" -WindowStyle Hidden
}
$extra = @($options -split ' ' | Where-Object { $_ })
$args = @('-Xmx4g', '-XX:+UseZGC', "-Xlog:gc,safepoint:file=$o\jvm.log:uptime", '--enable-native-access=ALL-UNNAMED',
  '-cp', 'out;out-test', 'organium.LiveStress',
  'D:\OrganiumSampleSets\OrganDefinitions\Melcer Chamber Music Hall.Organ_Hauptwerk_xml', '1', $o, '0', '-',
  "--stop-after=$seconds") + $extra
& java @args 2>&1 | Out-File -Encoding utf8 "$o\console-output.txt"
python -I D:\Organium\research\live\startup_gaps.py $o
