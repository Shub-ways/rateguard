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
    [int]$Requests = 12,
    # Fixed-window length configured for the stack (RATEGUARD_WINDOW), used to avoid boundaries.
    [int]$WindowSeconds = 60,
    # Wait for a fresh window if fewer seconds than this remain in the current one.
    [int]$MinSecondsLeft = 15
)

# Window boundaries are defined by the Redis server clock (rateguard.redis.time-source=REDIS_SERVER),
# so read that clock, not the local one.
function Get-RedisMillis {
    $t = docker compose exec -T redis redis-cli TIME 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $t) { return $null }
    return [long]$t[0] * 1000 + [math]::Floor([long]$t[1] / 1000)
}
$windowMs = [long]$WindowSeconds * 1000
$before = Get-RedisMillis
if ($null -ne $before) {
    $leftMs = $windowMs - ($before % $windowMs)
    if ($leftMs -lt $MinSecondsLeft * 1000) {
        "Only {0:n1} s left in the current window; waiting for the next one..." -f ($leftMs / 1000)
        Start-Sleep -Milliseconds ($leftMs + 200)
        $before = Get-RedisMillis
    }
} else {
    Write-Warning "Could not read the Redis clock (docker compose exec redis); window-boundary check skipped."
}

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

$after = Get-RedisMillis

$rows | Format-Table -AutoSize
if ($null -ne $before -and $null -ne $after) {
    "All requests inside one fixed window (Redis clock): {0}" -f ([math]::Floor($before / $windowMs) -eq [math]::Floor($after / $windowMs))
}
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
