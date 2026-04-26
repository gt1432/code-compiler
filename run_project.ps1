Write-Host ">>> Starting Lumina Code Compiler with Database Connectivity..." -ForegroundColor Cyan

# Locate JAVA_HOME
$jdkPath = "C:\Program Files\Java\jdk-22"
if (!(Test-Path $jdkPath)) {
    $jdkPath = (Get-ChildItem "C:\Program Files\Java\jdk*" | Sort-Object Name -Descending | Select-Object -First 1).FullName
}

if ($jdkPath) {
    $env:JAVA_HOME = $jdkPath
    Write-Host ">>> Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Gray
}

# Use Maven Wrapper to run
if (Test-Path ".\mvnw.cmd") {
    .\mvnw.cmd spring-boot:run
} else {
    Write-Host "Error: mvnw.cmd not found. Please ensure the project was initialized correctly." -ForegroundColor Red
}
