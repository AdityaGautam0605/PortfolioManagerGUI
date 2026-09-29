param(
    [Parameter(Mandatory=$true)][string]$Jdk,
    [Parameter(Mandatory=$true)][string]$JavaFx,
    [Parameter(Mandatory=$true)][string]$JsonJar
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$out = Join-Path $repo 'out/checks'
New-Item -ItemType Directory -Force $out | Out-Null
$sources = @(Get-ChildItem (Join-Path $repo 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $Jdk 'bin/javac.exe') --module-path $JavaFx --add-modules javafx.controls,javafx.swing -cp $JsonJar -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item (Join-Path $repo 'src/styles.css') $out
Copy-Item (Join-Path $repo 'src/image.jpeg') $out
$oldFinnhub = $env:FINNHUB_API_KEY
$oldAlpha = $env:ALPHAVANTAGE_API_KEY
try {
    $env:FINNHUB_API_KEY = 'test-only'
    $env:ALPHAVANTAGE_API_KEY = 'test-only'
    foreach ($test in @('StockManagerChecks', 'StockApiChecks', 'UiChecks')) {
        & (Join-Path $Jdk 'bin/java.exe') --enable-native-access=javafx.graphics --module-path $JavaFx --add-modules javafx.controls,javafx.swing -cp "$out;$JsonJar" $test $out
        if ($LASTEXITCODE -ne 0) { throw "$test failed" }
    }
} finally {
    $env:FINNHUB_API_KEY = $oldFinnhub
    $env:ALPHAVANTAGE_API_KEY = $oldAlpha
}
