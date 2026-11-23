<#
.SYNOPSIS
  Verifies that the Docker Compose stack enforces ONE shared quota across app-a and app-b.

.DESCRIPTION
  Sends sequential requests with one API key through the nginx load balancer, which
  alternates between the two instances. With a shared Redis quota of N per window,
  exactly N requests succeed in total, regardless of which instance served them.
  With a per-instance (in-memory) quota, 2 x N would succeed.

.EXAMPLE
  $env:RATEGUARD_API_KEYS = "key-1,key-2"; ./scripts/verify-shared-quota.ps1 -Requests 12
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = $null,
    [int]$Requests = 12
)

if (-not $ApiKey) {
    $keys = $env:RATEGUARD_API_KEYS
    if (-not $keys -and (Test-Path "$PSScriptRoot/../.env")) {
        $line = Select-String -Path "$PSScriptRoot/../.env" -Pattern '^RATEGUARD_API_KEYS=' | Select-Object -First 1
        if ($line) { $keys = $line.Line.Substring('RATEGUARD_API_KEYS='.Length) }
    }
    if (-not $keys) { throw "Pass -ApiKey or set RATEGUARD_API_KEYS (shell or .env)." }
    $ApiKey = ($keys -split ',')[0].Trim()
}

$rows = @()
for ($i = 1; $i -le $Requests; $i++) {
    $raw = & curl.exe -s -i -H "X-API-Key: $ApiKey" "$BaseUrl/api/v1/demo/resource"
    $status = [int](($raw | Select-Object -First 1) -split ' ')[1]
    $h = @{}
    foreach ($l in $raw) { if ($l -match '^([A-Za-z-]+):\s*(.*)$') { $h[$Matches[1].ToLower()] = $Matches[2].Trim() } }
    $rows += [pscustomobject]@{
        Request    = $i
        Status     = $status
        Instance   = $h['x-rateguard-instance']
        Limit      = $h['x-ratelimit-limit']
        Remaining  = $h['x-ratelimit-remaining']
        RetryAfter = $h['retry-after']
    }
}

$rows | Format-Table -AutoSize
"Accepted (200): {0}   Rejected (429): {1}   Other: {2}" -f `
    ($rows | Where-Object Status -eq 200).Count,
    ($rows | Where-Object Status -eq 429).Count,
    ($rows | Where-Object { $_.Status -notin 200, 429 }).Count
"Served by: " + (($rows | Group-Object Instance | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ', ')
$limit = ($rows | Where-Object Limit | Select-Object -First 1).Limit
if ($limit) {
    $ok = ($rows | Where-Object Status -eq 200).Count -le [int]$limit
    "Shared quota respected (accepted <= limit $limit across both instances): $ok"
}
