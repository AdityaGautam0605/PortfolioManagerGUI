param(
    [Parameter(Mandatory=$true)][string]$Jdk,
    [Parameter(Mandatory=$true)][string]$JavaFx,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [Parameter(Mandatory=$true)][string]$H2Jar
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
$previous = @{}
foreach ($key in @('DB_URL', 'DB_USER', 'DB_PASSWORD')) { $previous[$key] = [Environment]::GetEnvironmentVariable($key) }
try {
    New-Item -ItemType Directory -Force out/hosted | Out-Null
    $sources = @(Get-ChildItem src/portfolioManagerGUI -Filter '*.java' | ForEach-Object FullName)
    $sources += @('src/StockManager.java', 'src/DemoStockManager.java', 'src/HostedAuth.java', 'src/HostedIdentity.java', 'src/HostedStockManager.java', 'src/HostedSchema.java', 'src/WebServer.java', 'tests/HostedChecks.java')
    & "$Jdk/bin/javac.exe" --module-path $JavaFx --add-modules javafx.base,jdk.httpserver -cp $JsonJar -d out/hosted @sources
    if ($LASTEXITCODE -ne 0) { throw 'Hosted compilation failed.' }
    $env:DB_URL = 'jdbc:h2:mem:folio_hosted_checks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1'
    $env:DB_USER = 'sa'
    $env:DB_PASSWORD = 'test-only'
    & "$Jdk/bin/java.exe" --module-path $JavaFx --add-modules javafx.base,jdk.httpserver -cp "out/hosted;$JsonJar;$H2Jar" HostedChecks
    if ($LASTEXITCODE -ne 0) { throw 'Hosted checks failed.' }
} finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key]) }
    Pop-Location
}
