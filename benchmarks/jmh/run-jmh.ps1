<#
.SYNOPSIS
  Runs the RateGuard JMH sweep and the heap measurement; writes raw results to
  benchmarks/results/jmh/<timestamp>/.

.EXAMPLE
  mvn -DskipTests package          # builds benchmarks/jmh/target/benchmarks.jar
  ./benchmarks/jmh/run-jmh.ps1     # full sweep (~20 min on the reference laptop)
  ./benchmarks/jmh/run-jmh.ps1 -Quick   # 1 warm-up + 1 measurement iteration, for a smoke test
#>
param(
    [switch]$Quick,
    [int[]]$ThroughputThreads = @(1, 4, 12),
    [int[]]$LatencyThreads = @(1, 12),
    [switch]$SkipRedis,
    [switch]$SkipMemory
)

# Native tools (java, docker) write to stderr; in Windows PowerShell 5.1 'Stop' would turn that
# into terminating errors, so failures are detected through $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
$root = Resolve-Path "$PSScriptRoot/../.."
$jar = Join-Path $root 'benchmarks/jmh/target/benchmarks.jar'
if (-not (Test-Path $jar)) { throw "Build first: mvn -DskipTests package" }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $root "benchmarks/results/jmh/$stamp"
New-Item -ItemType Directory -Force $out | Out-Null

$iter = if ($Quick) { @('-wi', '1', '-i', '1', '-w', '1s', '-r', '1s') } else { @() }  # else: annotation defaults (3x2s warm-up, 5x2s measure)

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
[ordered]@{
    started = (Get-Date).ToString('o')
    gitCommit = (git -C $root rev-parse HEAD)
    gitDirty = [bool](git -C $root status --porcelain)
    cpu = $cpu.Name; logicalProcessors = $cpu.NumberOfLogicalProcessors
    ramGiB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
    os = (Get-CimInstance Win32_OperatingSystem).Caption + ' ' + (Get-CimInstance Win32_OperatingSystem).BuildNumber
    powerPlan = ((powercfg /getactivescheme) -replace '^.*\((.*)\).*$', '$1')
    java = ((cmd /c 'java -version 2>&1' | Select-Object -First 1) -replace '"', '')
    backgroundCpuPercentAtStart = (Get-CimInstance Win32_Processor | Measure-Object LoadPercentage -Average).Average
    quick = [bool]$Quick
} | ConvertTo-Json | Set-Content (Join-Path $out 'metadata.json') -Encoding utf8

function Run-Jmh([string]$name, [string]$pattern, [int]$threads, [string[]]$extra) {
    $json = Join-Path $out "$name-t$threads.json"
    $log = Join-Path $out "$name-t$threads.txt"
    Write-Host "== $name threads=$threads"
    & java -jar $jar $pattern -t $threads @iter -rf json -rff $json @extra *> $log
    if ($LASTEXITCODE -ne 0) { Write-Warning "$name t=$threads exited with $LASTEXITCODE (see $log)" }
}

foreach ($t in $ThroughputThreads) { Run-Jmh 'inmemory-throughput' 'InMemoryLimiterBenchmark.throughput' $t @() }
foreach ($t in $LatencyThreads)    { Run-Jmh 'inmemory-latency'    'InMemoryLimiterBenchmark.latency'    $t @() }

if (-not $SkipRedis) {
    docker rm -f rateguard-bench-redis 2>$null | Out-Null
    docker run -d --name rateguard-bench-redis -p 6380:6379 --cpus 1 redis:7.4.2-alpine `
        redis-server --save '' --appendonly no | Out-Null
    Start-Sleep -Seconds 2
    try {
        foreach ($t in $ThroughputThreads) { Run-Jmh 'redis-throughput' 'RedisLimiterBenchmark.throughput' $t @() }
        foreach ($t in $LatencyThreads)    { Run-Jmh 'redis-latency'    'RedisLimiterBenchmark.latency'    $t @() }
    } finally {
        docker rm -f rateguard-bench-redis | Out-Null
    }
}

if (-not $SkipMemory) {
    Write-Host '== memory footprint'
    & java -Xms2g -Xmx2g -cp $jar com.portfolio.ratelimiter.bench.MemoryFootprint *> (Join-Path $out 'memory-footprint.txt')
}

(Get-Content (Join-Path $out 'metadata.json') -Raw | ConvertFrom-Json) |
    Add-Member -NotePropertyName finished -NotePropertyValue (Get-Date).ToString('o') -PassThru |
    ConvertTo-Json | Set-Content (Join-Path $out 'metadata.json') -Encoding utf8
Write-Host "Results: $out"
