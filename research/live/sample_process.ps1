# Samples, once a second, the working set and private bytes of the JVM process whose command line
# matches <pattern>, and the system's free RAM, until that process exits. Writes a CSV.
#   sample_process.ps1 <pattern> <out.csv>
param([string]$pattern, [string]$out)
"t_s,working_set_mb,private_mb,free_ram_mb" | Set-Content -Encoding ascii $out
$t0 = Get-Date
$seen = $false
for ($i = 0; $i -lt 7200; $i++) {
  # the java.exe on PATH can be a launcher stub with the same command line; take the largest process
  $q = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match $pattern } |
    Sort-Object WorkingSetSize -Descending)
  if ($q.Count -eq 0) { if ($seen) { break } else { Start-Sleep -Seconds 1; continue } }
  $seen = $true
  $free = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory
  "{0:F0},{1:F0},{2:F0},{3:F0}" -f ((Get-Date) - $t0).TotalSeconds, ($q[0].WorkingSetSize / 1MB), ($q[0].PrivatePageCount / 1MB), ($free / 1KB) |
    Add-Content -Encoding ascii $out
  Start-Sleep -Seconds 1
}
