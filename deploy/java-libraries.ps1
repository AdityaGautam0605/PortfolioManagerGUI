# Standalone javac launchers need the same pinned pool dependencies as pom.xml.
$poolDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'out/dependencies'
New-Item -ItemType Directory -Force $poolDirectory | Out-Null
foreach ($artifact in @('com/zaxxer/HikariCP/7.1.0/HikariCP-7.1.0.jar', 'org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar')) {
    $destination = Join-Path $poolDirectory (Split-Path $artifact -Leaf)
    if (-not (Test-Path $destination)) {
        $temporary = "$destination.download"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/$artifact" -OutFile $temporary
            Move-Item -LiteralPath $temporary -Destination $destination -Force
        } finally {
            if (Test-Path $temporary) { Remove-Item -LiteralPath $temporary }
        }
    }
}
Join-Path $poolDirectory '*'
