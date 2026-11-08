# One LiveStress run with the given JVM options and garbage rate, plus process-memory sampling.
#   gc_compare.ps1 <run name> <seconds> <garbage MB/s> "<jvm options, space separated>"
param([string]$name, [int]$seconds, [double]$garbage, [string]$jvmOptions)
$jvm = @($jvmOptions -split ' ' | Where-Object { $_ })
Set-Location D:\Organium
$o = "D:\Organium\research\live\$name"
New-Item -ItemType Directory -Force $o | Out-Null
Start-Process powershell -ArgumentList '-NoProfile', '-File', 'D:\Organium\research\live\sample_process.ps1', 'LiveStress', "$o\process-memory.csv" -WindowStyle Hidden
$args = @($jvm) + @("-Xlog:gc,safepoint:file=$o\jvm.log:uptime", '--enable-native-access=ALL-UNNAMED', '-cp', 'out;out-test',
  'organium.LiveStress', 'D:\OrganiumSampleSets\OrganDefinitions\Melcer Chamber Music Hall.Organ_Hauptwerk_xml', "$seconds", $o, "$garbage")
& java @args 2>&1 | Out-File -Encoding utf8 "$o\console-output.txt"
Start-Sleep -Seconds 3
$mem = Import-Csv "$o\process-memory.csv"
$ws = ($mem | Measure-Object -Property working_set_mb -Maximum).Maximum
$free = ($mem | Measure-Object -Property free_ram_mb -Minimum).Minimum
"== $name  ($($jvm -join ' '), garbage $garbage MB/s)"
Get-Content "$o\summary.txt" | Select-String 'render time|callback gaps|garbage collection|heap in use|voices'
"   process: peak working set $ws MiB, min free RAM $free MiB"
