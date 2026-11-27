# Poll Docker Desktop from the Windows host and publish Prometheus text atomically.
# The exporter container only serves the resulting file; it never receives the Docker socket.
param(
    [string]$Project = $env:CONTAINER_METRICS_PROJECT,
    [string[]]$Services = @(),
    [ValidateRange(5, 3600)][int]$IntervalSeconds = 30,
    [switch]$Watch,
    [string]$OutputPath = (Join-Path $PSScriptRoot 'data/metrics.txt')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture

if ([string]::IsNullOrWhiteSpace($Project) -or $Project -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]*$') {
    throw 'Specify the exact Compose project with -Project or CONTAINER_METRICS_PROJECT.'
}

if ($Services.Count -eq 0) {
    if (-not [string]::IsNullOrWhiteSpace($env:CONTAINER_METRICS_SERVICES)) {
        $Services = @($env:CONTAINER_METRICS_SERVICES -split '\s*,\s*')
    } else {
        $Services = @(
            'web', 'backend', 'ai-service', 'db', 'order-worker', 'backoffice-worker',
            'cdc-worker', 'db-replica-1', 'db-replica-2', 'warehouse', 'kafka',
            'connect', 'import-storage', 'prometheus', 'blackbox-exporter',
            'nginx-exporter', 'grafana', 'tempo', 'otel-collector', 'infra-metrics',
            'pg-exporter-primary', 'pg-exporter-replica-1', 'pg-exporter-replica-2',
            'pg-exporter-warehouse', 'container-metrics-exporter'
        )
    }
}

$Services = @($Services | ForEach-Object { $_ -split '\s*,\s*' })
$allowedServices = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($service in $Services) {
    if ($service -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]*$') {
        throw "Invalid service name: $service"
    }
    [void]$allowedServices.Add($service)
}

function Format-Number([double]$Value) {
    return $Value.ToString('R', $culture)
}

function Convert-DockerSize([string]$Text) {
    $match = [regex]::Match($Text, '^\s*(\d+(?:\.\d+)?)\s*([kMGTPE]?i?B)\s*$', 'IgnoreCase')
    if (-not $match.Success) { throw "Unexpected Docker size format: $Text" }
    $factor = switch ($match.Groups[2].Value) {
        B   { 1; break }
        kB  { 1e3; break }
        MB  { 1e6; break }
        GB  { 1e9; break }
        TB  { 1e12; break }
        PB  { 1e15; break }
        EB  { 1e18; break }
        KiB { 1KB; break }
        MiB { 1MB; break }
        GiB { 1GB; break }
        TiB { 1TB; break }
        PiB { 1PB; break }
        EiB { [math]::Pow(1024, 6); break }
        default { throw "Unexpected Docker size unit: $($match.Groups[2].Value)" }
    }
    return [double]::Parse($match.Groups[1].Value, $culture) * [double]$factor
}

function Convert-DockerPair([string]$Text) {
    $parts = $Text -split '\s*/\s*'
    if ($parts.Count -ne 2) { throw "Unexpected Docker I/O format: $Text" }
    return @((Convert-DockerSize $parts[0]), (Convert-DockerSize $parts[1]))
}

function Escape-Label([string]$Value) {
    return $Value.Replace('\', '\\').Replace("`n", '\n').Replace('"', '\"')
}

function Add-Sample(
    [Collections.Generic.List[string]]$Lines,
    [string]$Name,
    [string]$Labels,
    [double]$Value
) {
    [void]$Lines.Add(('{0}{{{1}}} {2}' -f $Name, $Labels, (Format-Number $Value)))
}

function Publish-Text([string]$Path, [string]$Content) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $directory = [IO.Path]::GetDirectoryName($resolved)
    [void][IO.Directory]::CreateDirectory($directory)
    $temporary = Join-Path $directory ('.metrics-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backup = Join-Path $directory ('.metrics-' + [guid]::NewGuid().ToString('N') + '.bak')
    try {
        [IO.File]::WriteAllText($temporary, $Content, [Text.UTF8Encoding]::new($false))
        if ([IO.File]::Exists($resolved)) {
            # .NET Framework in Windows PowerShell 5.1 requires a backup path.
            [IO.File]::Replace($temporary, $resolved, $backup)
        } else {
            [IO.File]::Move($temporary, $resolved)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
        if ([IO.File]::Exists($backup)) { [IO.File]::Delete($backup) }
    }
}

function Collect-Metrics {
    $ids = @(& docker ps --all --quiet --filter "label=com.docker.compose.project=$Project" 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'docker ps failed; check Docker Desktop.' }
    if ($ids.Count -eq 0) { throw "No Compose containers found for project $Project." }

    $inspectJson = (& docker inspect @ids 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'docker inspect failed; a container may have changed during polling.' }
    # Windows PowerShell 5.1 returns the JSON array as one pipeline object;
    # PowerShell 7 unrolls it. Assignment followed by piping handles both.
    $inspected = ConvertFrom-Json -InputObject $inspectJson
    $containers = @(
        $inspected | Where-Object {
            $labels = $_.Config.Labels
            $labels.'com.docker.compose.project' -eq $Project -and
            $allowedServices.Contains([string]$labels.'com.docker.compose.service') -and
            $labels.'com.docker.compose.oneoff' -ne 'True'
        }
    )
    if ($containers.Count -eq 0) { throw 'No selected Compose service containers found.' }

    $running = @($containers | Where-Object { $_.State.Status -eq 'running' })
    $statsById = @{}
    if ($running.Count -gt 0) {
        $runningIds = @($running | ForEach-Object { $_.Id })
        $statsLines = @(& docker stats --no-stream --format '{{json .}}' @runningIds 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'docker stats failed; a container may have changed during polling.' }
        foreach ($line in $statsLines) {
            $stat = ConvertFrom-Json -InputObject ([string]$line)
            $statsById[[string]$stat.ID] = $stat
        }
    }

    $lines = [Collections.Generic.List[string]]::new()
    foreach ($definition in @(
        'docker_container_cpu_percent|gauge|CPU use as reported by Docker stats; 100 percent is one CPU core.',
        'docker_container_cpu_limit_cores|gauge|Configured CPU quota in cores; zero means unlimited.',
        'docker_container_memory_usage_bytes|gauge|Memory use as reported by Docker stats.',
        'docker_container_memory_limit_bytes|gauge|Configured memory limit in bytes; zero means unlimited.',
        'docker_container_network_receive_bytes_total|counter|Received network bytes as reported by Docker stats.',
        'docker_container_network_transmit_bytes_total|counter|Transmitted network bytes as reported by Docker stats.',
        'docker_container_block_read_bytes_total|counter|Block bytes read as reported by Docker stats.',
        'docker_container_block_write_bytes_total|counter|Block bytes written as reported by Docker stats.',
        'docker_container_restarts_total|counter|Docker restart count for this container instance.',
        'docker_container_state|gauge|One for the current Docker state and zero for other states.',
        'docker_container_collector_containers|gauge|Number of selected Compose containers in this poll.',
        'docker_container_collector_last_success_timestamp_seconds|gauge|Unix time of the last completed poll.'
    )) {
        $parts = $definition.Split('|', 3)
        [void]$lines.Add("# HELP $($parts[0]) $($parts[2])")
        [void]$lines.Add("# TYPE $($parts[0]) $($parts[1])")
    }

    $states = @('created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead')
    foreach ($container in $containers) {
        $labels = $container.Config.Labels
        $service = [string]$labels.'com.docker.compose.service'
        $replica = [string]$labels.'com.docker.compose.container-number'
        if ($replica -notmatch '^\d+$') { continue }
        $sampleLabels = 'project="{0}",service="{1}",replica="{2}"' -f
            (Escape-Label $Project), (Escape-Label $service), (Escape-Label $replica)

        Add-Sample $lines 'docker_container_restarts_total' $sampleLabels ([double]$container.RestartCount)
        Add-Sample $lines 'docker_container_memory_limit_bytes' $sampleLabels ([double]$container.HostConfig.Memory)
        $cpuLimit = 0.0
        if ([double]$container.HostConfig.NanoCpus -gt 0) {
            $cpuLimit = [double]$container.HostConfig.NanoCpus / 1e9
        } elseif ([double]$container.HostConfig.CpuQuota -gt 0 -and [double]$container.HostConfig.CpuPeriod -gt 0) {
            $cpuLimit = [double]$container.HostConfig.CpuQuota / [double]$container.HostConfig.CpuPeriod
        }
        Add-Sample $lines 'docker_container_cpu_limit_cores' $sampleLabels $cpuLimit

        foreach ($state in $states) {
            $stateLabels = $sampleLabels + ',state="' + $state + '"'
            $stateValue = if ($container.State.Status -eq $state) { 1.0 } else { 0.0 }
            Add-Sample $lines 'docker_container_state' $stateLabels $stateValue
        }

        if ($container.State.Status -ne 'running') { continue }
        $shortId = ([string]$container.Id).Substring(0, 12)
        if (-not $statsById.ContainsKey($shortId)) { throw "Missing Docker stats for $service replica $replica." }
        $stat = $statsById[$shortId]
        $memory = Convert-DockerPair ([string]$stat.MemUsage)
        $network = Convert-DockerPair ([string]$stat.NetIO)
        $block = Convert-DockerPair ([string]$stat.BlockIO)
        $cpu = [double]::Parse(([string]$stat.CPUPerc).Trim().TrimEnd('%'), $culture)
        Add-Sample $lines 'docker_container_cpu_percent' $sampleLabels $cpu
        Add-Sample $lines 'docker_container_memory_usage_bytes' $sampleLabels $memory[0]
        Add-Sample $lines 'docker_container_network_receive_bytes_total' $sampleLabels $network[0]
        Add-Sample $lines 'docker_container_network_transmit_bytes_total' $sampleLabels $network[1]
        Add-Sample $lines 'docker_container_block_read_bytes_total' $sampleLabels $block[0]
        Add-Sample $lines 'docker_container_block_write_bytes_total' $sampleLabels $block[1]
    }

    $projectLabel = 'project="' + (Escape-Label $Project) + '"'
    Add-Sample $lines 'docker_container_collector_containers' $projectLabel $containers.Count
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    Add-Sample $lines 'docker_container_collector_last_success_timestamp_seconds' $projectLabel $timestamp
    Publish-Text $OutputPath (($lines -join "`n") + "`n")
    Write-Host "Published metrics for $($containers.Count) containers of $Project at $([DateTimeOffset]::UtcNow.ToString('u'))."
}

do {
    try {
        Collect-Metrics
    } catch {
        if (-not $Watch) { throw }
        Write-Warning "Container metrics poll failed: $($_.Exception.Message)"
    }
    if ($Watch) { Start-Sleep -Seconds $IntervalSeconds }
} while ($Watch)
