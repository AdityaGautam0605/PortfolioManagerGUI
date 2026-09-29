param(
    [switch]$Demo,
    [int]$Port = 8080,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$JavaFx,
    [string]$JsonJar,
    [string]$MySqlJar,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
$driveRoot = [System.IO.Path]::GetPathRoot($repo)
if (-not $Jdk) {
    $compiler = Get-Command javac -ErrorAction SilentlyContinue
    if ($compiler) { $Jdk = Split-Path (Split-Path $compiler.Source -Parent) -Parent }
    elseif (Test-Path (Join-Path $driveRoot 'openjdk25/bin/javac.exe')) { $Jdk = Join-Path $driveRoot 'openjdk25' }
}
if (-not $JavaFx) { $JavaFx = Join-Path $driveRoot 'openjfx-25.0.1_windows-x64_bin-sdk/javafx-sdk-25.0.1/lib' }
if (-not $JsonJar) { $JsonJar = Join-Path $env:USERPROFILE '.m2/repository/org/json/json/20240303/json-20240303.jar' }
if (-not $MySqlJar) { $MySqlJar = Join-Path $env:USERPROFILE '.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar' }
if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin/javac.exe'))) { throw 'Set JAVA_HOME or pass -Jdk with the path to JDK 25.' }
if (-not (Test-Path (Join-Path $JavaFx 'javafx.base.jar'))) { throw 'Pass -JavaFx with the JavaFX SDK lib directory.' }
if (-not (Test-Path $JsonJar)) { throw 'Pass -JsonJar with the org.json JAR path.' }
if (-not $Demo -and -not (Test-Path $MySqlJar)) { throw 'Pass -MySqlJar with the MySQL Connector/J JAR path.' }
Push-Location $repo
try {
    if (-not $SkipBuild) {
        if (-not (Test-Path 'web/node_modules')) {
            & npm.cmd ci --prefix web
            if ($LASTEXITCODE -ne 0) { throw 'Web dependency installation failed' }
        }
        & npm.cmd run build --prefix web
        if ($LASTEXITCODE -ne 0) { throw 'Web build failed' }
    }
    if (-not (Test-Path 'web/dist/index.html')) { throw 'Build the web UI before using -SkipBuild.' }
    $out = Join-Path $repo 'out/web'
    New-Item -ItemType Directory -Force $out | Out-Null
    $sources = @(Get-ChildItem (Join-Path $repo 'src/portfolioManagerGUI') -Filter '*.java' | ForEach-Object FullName)
    $sources += @('src/StockManager.java', 'src/DemoStockManager.java', 'src/WebServer.java')
    & (Join-Path $Jdk 'bin/javac.exe') --module-path $JavaFx --add-modules javafx.base,jdk.httpserver -cp $JsonJar -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'Java API compilation failed' }
    $classPath = "$out;$JsonJar;$(Join-Path $repo 'src')"
    if (-not $Demo) { $classPath += ";$MySqlJar" }
    $serverArgs = @('--port', "$Port", '--assets', (Join-Path $repo 'web/dist'))
    if ($Demo) { $serverArgs += '--demo' }
    & (Join-Path $Jdk 'bin/java.exe') --module-path $JavaFx --add-modules javafx.base,jdk.httpserver -cp $classPath WebServer @serverArgs
} finally { Pop-Location }
