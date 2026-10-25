$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$distribution = '8.9'
$zipUrl = "https://services.gradle.org/distributions/gradle-$distribution-bin.zip"
$base = Join-Path $env:USERPROFILE '.gradle\dubstudio-bootstrap'
$home = Join-Path $base "gradle-$distribution"
$exe = Join-Path $home 'bin\gradle.bat'
if (!(Test-Path $exe)) {
    New-Item -ItemType Directory -Path $base -Force | Out-Null
    $zip = Join-Path $base "gradle-$distribution-bin.zip"
    Write-Host "Downloading Gradle $distribution from official distribution server..."
    Invoke-WebRequest -Uri $zipUrl -OutFile $zip
    $hashUrl = "$zipUrl.sha256"
    $expected = (Invoke-WebRequest -Uri $hashUrl).Content.Trim().Split(' ')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { Remove-Item $zip -Force; throw 'Gradle SHA256 mismatch' }
    Expand-Archive -Path $zip -DestinationPath $base -Force
}
if (!(Test-Path $exe)) { throw 'Gradle bootstrap failed' }
& $exe --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Done: app\build\outputs\apk\debug\app-debug.apk"
