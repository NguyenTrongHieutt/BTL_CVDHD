# Khôi phục dữ liệu demo trên Windows PowerShell (cùng thứ tự với deploy/reset-demo, AD-18).
# Chạy từ thư mục gốc repo:  powershell -ExecutionPolicy Bypass -File deploy/reset-demo.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path ".env")) { throw "Thiếu deploy/.env — sao chép từ deploy/.env.example rồi đổi giá trị." }
$compose = @("compose", "-f", "docker-compose.yml", "--env-file", ".env")

# Windows PowerShell 5.1 không tự dừng khi docker.exe trả mã lỗi.
function Invoke-Compose {
    & docker @compose @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose thất bại (mã $LASTEXITCODE): $args" }
}

function Wait-Healthy([string]$service) {
    $id = (Invoke-Compose ps -q $service)
    if (-not $id) { throw "Không tìm thấy container $service." }
    for ($i = 0; $i -lt 90; $i++) {
        $status = (& docker inspect -f "{{.State.Health.Status}}" $id 2>$null)
        if ($LASTEXITCODE -ne 0) { throw "Không đọc được trạng thái container $service." }
        if ($status -eq "healthy") { Write-Host "  ${service}: healthy"; return }
        Start-Sleep -Seconds 2
    }
    throw "$service không lên được, xem: docker compose logs $service"
}

& (Join-Path $PSScriptRoot 'check-docker-storage.ps1')
if (-not $NoBuild) { Write-Host "0/6 Build image (một lần)"; Invoke-Compose build }
Write-Host "1/6 Xóa container và volume cũ"; Invoke-Compose down -v --remove-orphans
Write-Host "2/6 Bật db"; Invoke-Compose up -d --no-build db; Wait-Healthy "db"
Write-Host "3/6 Bật backend (chạy Flyway)"; Invoke-Compose up -d --no-build backend; Wait-Healthy "backend"
Write-Host "4/6 Nạp dữ liệu mẫu"; Invoke-Compose --profile tools run --rm seed
Write-Host "5/6 Bật ai-service"; Invoke-Compose up -d --no-build ai-service
Write-Host "6/6 Bật web"; Invoke-Compose up -d --no-build web
$endpoint = Invoke-Compose port web 80 | Select-Object -First 1
if ($endpoint -notmatch ':([0-9]+)$') { throw "Không xác định được cổng web." }
Write-Host "Xong. Mở http://localhost:$($Matches[1])"
