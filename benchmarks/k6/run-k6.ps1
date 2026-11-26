<#
.SYNOPSIS
  Runs the k6 capacity scenarios (A-D) and the distributed correctness test;
  writes raw results to benchmarks/results/k6/<timestamp>/.

.DESCRIPTION
  Capacity: per scenario and VU level, one warm-up run (saved, excluded from stats)
  then N trials. Limit = 10^9 per 60 s so 429s do not dominate.
  Correctness: separate stack configuration (limit 100 per 60 s), 1,000 requests
  from one client through nginx, window-boundary guarded by the Redis clock.

.EXAMPLE
  docker compose build              # builds rateguard-service:local (needs the jar: mvn -DskipTests package)
  ./benchmarks/k6/run-k6.ps1
  ./benchmarks/k6/run-k6.ps1 -Scenarios B -Vus 10 -Trials 1 -Duration 10s -SkipCorrectness   # smoke test
#>
param(
    [string[]]$Scenarios = @('A', 'B', 'C', 'D'),
    [int[]]$Vus = @(10, 25, 50),
    [int]$Trials = 3,
    [string]$Duration = '30s',
    [string]$Warmup = '15s',
    [int]$CorrectnessTrials = 3,
    [switch]$SkipCapacity,
    [switch]$SkipCorrectness,
    [switch]$RawOutput
)

# Native tools (java, docker) write to stderr; in Windows PowerShell 5.1 'Stop' would turn that
# into terminating errors, so failures are detected through $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
$root = Resolve-Path "$PSScriptRoot/../.."
$compose = Join-Path $root 'benchmarks/k6/compose.bench.yaml'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$rel = "$stamp"                                   # path below /results inside the k6 container
$out = Join-Path $root "benchmarks/results/k6/$stamp"
New-Item -ItemType Directory -Force $out | Out-Null

# Ephemeral benchmark-only API keys (50 clients); never written to the repository.
function New-Key { -join ((48..57) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ }) }
$keys = 1..50 | ForEach-Object { New-Key }
$env:RG_API_KEYS = $keys -join ','

$scenarioConfig = @{
    A = @{ profile = 'single';      target = 'http://app:8080'; enabled = 'false'; backend = 'IN_MEMORY'; desc = 'baseline: rate limiting disabled' }
    B = @{ profile = 'single';      target = 'http://app:8080'; enabled = 'true';  backend = 'IN_MEMORY'; desc = 'in-memory fixed window' }
    C = @{ profile = 'single';      target = 'http://app:8080'; enabled = 'true';  backend = 'REDIS';     desc = 'Redis fixed window, 1 instance' }
    D = @{ profile = 'distributed'; target = 'http://proxy:80'; enabled = 'true';  backend = 'REDIS';     desc = 'Redis fixed window, 2 instances behind nginx' }
}

function Compose { & docker compose -f $compose @args }

function Stack-Up([string]$profile, [string]$enabled, [string]$backend, [string]$limit, [string]$window) {
    $env:RG_ENABLED = $enabled; $env:RG_BACKEND = $backend; $env:RG_LIMIT = $limit; $env:RG_WINDOW = $window
    Compose --profile single --profile distributed down --remove-orphans *> $null
    Compose --profile $profile up -d --wait --force-recreate *> (Join-Path $out "compose-up-$profile.log")
    if ($LASTEXITCODE -ne 0) { throw "stack ($profile) failed to become healthy" }
}

function Stack-Down { Compose --profile single --profile distributed down --remove-orphans *> $null }

function Host-Cpu { (Get-CimInstance Win32_Processor | Measure-Object LoadPercentage -Average).Average }

function Start-StatsSampler([string]$file) {
    $stop = "$file.stop"
    Start-Job -ArgumentList $file, $stop -ScriptBlock {
        param($file, $stop)
        while (-not (Test-Path $stop)) {
            $ts = (Get-Date).ToString('o')
            docker stats --no-stream --format '{{json .}}' 2>$null | ForEach-Object { "{`"ts`":`"$ts`",`"s`":$_}" } |
                Add-Content -Path $file -Encoding utf8
        }
    }
}

function Stop-StatsSampler($job, [string]$file) {
    New-Item -ItemType File "$file.stop" -Force | Out-Null
    Wait-Job $job -Timeout 20 | Out-Null; Remove-Job $job -Force
    Remove-Item "$file.stop" -ErrorAction SilentlyContinue
}

function Run-K6([string]$script, [hashtable]$vars, [string]$summaryRel, [string]$rawRel) {
    $envArgs = @(); foreach ($k in $vars.Keys) { $envArgs += @('-e', "$k=$($vars[$k])") }
    $extra = @(); if ($rawRel) { $extra = @('--out', "json=/results/$rawRel") }
    Compose --profile tools run --rm -e "SUMMARY_FILE=/results/$summaryRel" @envArgs k6 run --quiet @extra "/scripts/$script" 2>&1 |
        Where-Object { $_ -notmatch '^\s*$' } | ForEach-Object { "$_" }
}

function Redis-Millis {
    $t = Compose exec -T redis redis-cli TIME
    [long]$t[0] * 1000 + [math]::Floor([long]$t[1] / 1000)
}

$meta = [ordered]@{
    started = (Get-Date).ToString('o'); gitCommit = (git -C $root rev-parse HEAD); gitDirty = [bool](git -C $root status --porcelain)
    cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
    docker = (docker version --format '{{.Server.Version}}'); dockerVm = (docker info --format 'cpus={{.NCPU}} mem={{.MemTotal}}')
    k6Image = 'grafana/k6:1.4.0'; appImage = (docker image inspect rateguard-service:local --format '{{.Id}} created {{.Created}}')
    containerLimits = 'app: 2 CPU / 1 GiB each; redis: 1 CPU; proxy: 1 CPU; k6: 4 CPU'
    capacity = @{ scenarios = $Scenarios; vus = $Vus; trials = $Trials; duration = $Duration; warmup = $Warmup; limit = '1000000000 per 60s'; clients = 50 }
    correctness = @{ trials = $CorrectnessTrials; limit = '100 per 60s'; requests = 1000; vus = 50 }
}
$meta | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'metadata.json') -Encoding utf8

if (-not $SkipCapacity) {
    foreach ($s in $Scenarios) {
        $cfg = $scenarioConfig[$s]
        Write-Host "=== Scenario $s ($($cfg.desc))"
        Stack-Up $cfg.profile $cfg.enabled $cfg.backend '1000000000' '60s'
        foreach ($v in $Vus) {
            $dir = "capacity/$s/vus$v"; New-Item -ItemType Directory -Force (Join-Path $out $dir) | Out-Null
            $common = @{ TARGET = $cfg.target; VUS = "$v"; KEYS = ($keys -join ',') }
            Run-K6 'capacity.js' ($common + @{ DURATION = $Warmup; LABEL = "$s vus=$v warmup" }) "$rel/$dir/warmup.json" $null
            for ($t = 1; $t -le $Trials; $t++) {
                $statsFile = Join-Path $out "$dir/trial$t-docker-stats.jsonl"
                $hostCpu = Host-Cpu
                $job = Start-StatsSampler $statsFile
                $raw = if ($RawOutput) { "$rel/$dir/trial$t-raw.json.gz" } else { $null }
                Run-K6 'capacity.js' ($common + @{ DURATION = $Duration; LABEL = "$s vus=$v trial=$t" }) "$rel/$dir/trial$t.json" $raw
                Stop-StatsSampler $job $statsFile
                @{ hostCpuPercentBeforeTrial = $hostCpu } | ConvertTo-Json | Set-Content (Join-Path $out "$dir/trial$t-host.json")
            }
        }
        Stack-Down
    }
}

if (-not $SkipCorrectness) {
    Write-Host '=== Correctness: 2 instances + Redis, quota 100 per 60 s, 1,000 requests, 1 client'
    Stack-Up 'distributed' 'true' 'REDIS' '100' '60s'
    New-Item -ItemType Directory -Force (Join-Path $out 'correctness') | Out-Null
    for ($t = 1; $t -le $CorrectnessTrials; $t++) {
        $windowMs = 60000
        $before = Redis-Millis
        $left = $windowMs - ($before % $windowMs)
        if ($left -lt 20000) { Start-Sleep -Milliseconds ($left + 300); $before = Redis-Millis }
        Run-K6 'correctness.js' @{ TARGET = 'http://proxy:80'; KEY = $keys[$t - 1]; VUS = '50'; ITERATIONS = '1000'; LIMIT = '100'; LABEL = "correctness trial=$t" } "$rel/correctness/trial$t.json" $null
        $after = Redis-Millis
        @{ redisMillisBefore = $before; redisMillisAfter = $after; windowMs = $windowMs
           sameWindow = ([math]::Floor($before / $windowMs) -eq [math]::Floor($after / $windowMs)) } |
            ConvertTo-Json | Set-Content (Join-Path $out "correctness/trial$t-window.json")
    }
    Stack-Down
}

Remove-Item Env:RG_API_KEYS, Env:RG_ENABLED, Env:RG_BACKEND, Env:RG_LIMIT, Env:RG_WINDOW -ErrorAction SilentlyContinue
(Get-Content (Join-Path $out 'metadata.json') -Raw | ConvertFrom-Json) |
    Add-Member -NotePropertyName finished -NotePropertyValue (Get-Date).ToString('o') -PassThru |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'metadata.json') -Encoding utf8
Write-Host "Results: $out"
