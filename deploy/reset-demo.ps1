# Khôi phục dữ liệu demo trên Windows PowerShell (cùng thứ tự với deploy/reset-demo, AD-18).
# Chạy từ thư mục gốc repo:  powershell -ExecutionPolicy Bypass -File deploy/reset-demo.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path ".env")) { throw "Thiếu deploy/.env — sao chép từ deploy/.env.example rồi đổi giá trị." }
$compose = @("compose", "-f", "docker-compose.yml", "--env-file", ".env")

function Wait-Healthy([string]$service) {
    $id = (& docker @compose ps -q $service)
    for ($i = 0; $i -lt 90; $i++) {
        $status = (& docker inspect -f "{{.State.Health.Status}}" $id 2>$null)
        if ($status -eq "healthy") { Write-Host "  ${service}: healthy"; return }
        Start-Sleep -Seconds 2
    }
    throw "$service không lên được, xem: docker compose logs $service"
}

if (-not $NoBuild) { Write-Host "0/6 Build image (một lần)"; & docker @compose build }
Write-Host "1/6 Xóa container và volume cũ"; & docker @compose down -v --remove-orphans
Write-Host "2/6 Bật db"; & docker @compose up -d --no-build db; Wait-Healthy "db"
Write-Host "3/6 Bật backend (chạy Flyway)"; & docker @compose up -d --no-build backend; Wait-Healthy "backend"
Write-Host "4/6 Nạp dữ liệu mẫu"; & docker @compose --profile tools run --rm seed
Write-Host "5/6 Bật ai-service"; & docker @compose up -d --no-build ai-service
Write-Host "6/6 Bật web"; & docker @compose up -d --no-build web
Write-Host "Xong. Mở http://localhost"
