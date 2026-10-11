# ExpensePilot Windows localhost diagnostic. No rebuild and no data deletion.
# Run from the repository root:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\ops\check-windows.ps1
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    Write-Host "Checking Docker Compose services..." -ForegroundColor Cyan
    docker compose ps -a
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose is unavailable. Start Docker Desktop (Linux containers)."
    }

    $healthUrl = "http://127.0.0.1:8080/actuator/health"
    $homeUrl = "http://127.0.0.1:8080/"
    $ready = $false
    Write-Host "Waiting up to 180 seconds for Spring Boot HTTP readiness..."
    for ($attempt = 0; $attempt -lt 36; $attempt++) {
        try {
            $health = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 4
            if ($health.StatusCode -eq 200 -and $health.Content -match '"status"\s*:\s*"UP"') {
                $ready = $true
                break
            }
        }
        catch {
            # JVM initialization and Flyway migrations can take some time.
        }
        Start-Sleep -Seconds 5
    }

    if (-not $ready) {
        Write-Host "Backend never reached UP. Last 150 app log lines:" -ForegroundColor Red
        docker compose logs --tail 150 app
        throw "Backend is not responding at $healthUrl"
    }

    Write-Host "Backend health: UP" -ForegroundColor Green

    $resources = @(
        @{ Url = $homeUrl; Text = "ExpensePilot" },
        @{ Url = "http://127.0.0.1:8080/styles.css"; Text = ".sidebar" },
        @{ Url = "http://127.0.0.1:8080/app.js"; Text = "ExpensePilot" }
    )
    foreach ($resource in $resources) {
        $response = Invoke-WebRequest -Uri $resource.Url -UseBasicParsing -TimeoutSec 15
        if ($response.StatusCode -ne 200 -or -not $response.Content.Contains($resource.Text)) {
            throw "Unexpected frontend response: $($resource.Url)"
        }
        Write-Host "HTTP 200: $($resource.Url)" -ForegroundColor Green
    }

    Write-Host "All checks passed. Open http://127.0.0.1:8080/" -ForegroundColor Green
}
finally {
    Pop-Location
}
