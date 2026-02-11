# Check and Setup Environment
$toolsDir = Join-Path $PSScriptRoot "tools"
$jdkZip = Join-Path $toolsDir "jdk.zip"
$mavenZip = Join-Path $toolsDir "maven.zip"

Write-Host "Checking prerequisites..."

# 1. Extract JDK
$jdkDir = Get-ChildItem -Path $toolsDir -Directory -Filter "jdk-*" | Select-Object -First 1
if (-not $jdkDir) {
    if (Test-Path $jdkZip) {
        Write-Host "Extracting JDK..."
        Expand-Archive -Path $jdkZip -DestinationPath $toolsDir -Force
        $jdkDir = Get-ChildItem -Path $toolsDir -Directory -Filter "jdk-*" | Select-Object -First 1
    } else {
        Write-Error "JDK zip not found in tools/ and no extracted JDK directory found."
        exit 1
    }
}
$env:JAVA_HOME = $jdkDir.FullName
$env:PATH = "$($jdkDir.FullName)\bin;$env:PATH"

# 2. Extract Maven
$mavenDir = Get-ChildItem -Path $toolsDir -Directory -Filter "apache-maven-*" | Select-Object -First 1
if (-not $mavenDir) {
    if (Test-Path $mavenZip) {
        Write-Host "Extracting Maven..."
        Expand-Archive -Path $mavenZip -DestinationPath $toolsDir -Force
        $mavenDir = Get-ChildItem -Path $toolsDir -Directory -Filter "apache-maven-*" | Select-Object -First 1
    } else {
        Write-Error "Maven zip not found in tools/ and no extracted Maven directory found."
        exit 1
    }
}
$env:MAVEN_HOME = $mavenDir.FullName
$env:PATH = "$($mavenDir.FullName)\bin;$env:PATH"

# 3. Verify
Write-Host "Verifying versions..."
java -version
mvn -version

Write-Host "Environment configured successfully for this session."
