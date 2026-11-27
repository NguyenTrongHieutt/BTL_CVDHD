# Kiểm tra dung lượng trước khi build hoặc nạp dữ liệu demo trên Docker Desktop WSL 2.
param(
    [string]$DiskImage = (Join-Path $env:LOCALAPPDATA 'Docker/wsl/disk/docker_data.vhdx'),
    [ValidateRange(1, 1024)][int]$MinFreeGiB = 16,
    [ValidateRange(1, 1024)][int]$MaxImageGiB = 32
)

$path = [IO.Path]::GetFullPath($DiskImage)
$drive = New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($path))
$image = Get-Item -LiteralPath $path -ErrorAction SilentlyContinue
$imageBytes = if ($image) { $image.Length } else { 0 }

Write-Host ('Docker {0} GiB, ổ {1} còn {2} GiB.' -f
    [math]::Round($imageBytes / 1GB, 2), $drive.Name, [math]::Round($drive.AvailableFreeSpace / 1GB, 2))

if ($drive.AvailableFreeSpace -lt $MinFreeGiB * 1GB) {
    throw "Ổ chứa Docker cần tối thiểu $MinFreeGiB GiB trống để chạy tác vụ này."
}
if ($imageBytes -ge $MaxImageGiB * 1GB) {
    throw "Đĩa Docker đã đạt ngưỡng $MaxImageGiB GiB. Xem dung lượng image, cache và volume bằng docker system df."
}
