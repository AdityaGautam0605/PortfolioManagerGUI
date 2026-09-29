# Regression checks

Run from PowerShell with the installed JDK, JavaFX library directory, and org.json JAR:

```powershell
./tests/run-checks.ps1 -Jdk 'D:\openjdk25' -JavaFx 'D:\openjfx-25.0.1_windows-x64_bin-sdk\javafx-sdk-25.0.1\lib' -JsonJar 'C:\path\to\json-20240303.jar'
```

The checks compile the application, exercise database/provider failures, partial price updates, and JavaFX background work. They use fake database connections, intercepted HTTP responses, and sample portfolio data. No live database or network access is required and no trades are made. The runner temporarily supplies dummy API keys and restores the environment afterward.

The UI checks render an invisible JavaFX window on a desktop session and save screenshots under the ignored `out/checks` directory. They verify that a blocked database read does not block the UI, duplicate submissions are disabled, failed trades preserve inputs, a successful trade is still reported as saved if reloading fails, and stale chart responses cannot replace the selected stock.

Live MySQL schema compatibility and real provider credentials still require integration testing in the configured environment.
