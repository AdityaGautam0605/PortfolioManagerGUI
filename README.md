# Folio portfolio workspace

A React + Vite web interface for the existing Java/MySQL portfolio manager. The new UI has Overview, Holdings, Stock Library, Watchlist, Settings, and individual stock pages. It runs locally; Java keeps database credentials and API keys off the browser.

## Hosted multi-user deployment

The opt-in `WebServer --hosted` mode adds registration, sign-in, and separate portfolios backed by Neon PostgreSQL. Render builds the included Dockerfile; Vercel forwards `/api` requests to Render. See [the deployment guide](deploy/README.md) for the account setup, required secrets, and final connection steps. Local mode remains single-user; existing local holdings are not imported automatically.

## Run from IntelliJ

Run `Main` as before. It now builds the React interface, starts the Java web server using your configured portfolio, and opens your browser. Node.js/npm must be available to the IDE. Building Java alone compiles the launcher; running it builds and opens the web UI.

Use program arguments `--demo` for sample data, `--no-open` to skip opening the browser, or `--skip-build` to reuse an existing web bundle. If port 8080 is occupied, the launcher opens a different available port instead of accidentally showing an older demo server. An explicit `--port 8081` can also be used.

The previous JavaFX interface is available only by explicitly running `LegacyDesktopApp`.

## Run the new interface

From the repository root in PowerShell:

```powershell
./start-web.ps1 -Demo
```

Open http://127.0.0.1:8080. Demo mode uses explicit sample holdings, prices, and generated history. It never connects to MySQL or quote providers. Demo trades are in memory and reset when the server restarts.

For your real portfolio, use the existing ignored `src/config.properties` or environment variables described in `src/config.properties.example`, then run:

```powershell
./start-web.ps1
```

Connected mode reads and writes the configured database. Buy/sell actions record portfolio entries; they do not submit brokerage orders. No database migration is introduced. The existing `stocks` and `portfolio` tables are required.

The launcher builds the web bundle and Java server, then binds only to `127.0.0.1`. Stop it with Ctrl+C. Pass `-Port 8081` if the default port is occupied, or `-SkipBuild` to reuse the web bundle after a previous build.

### Dependencies

Node.js 22.12+ (24 works), JDK 25, JavaFX 25's `lib` folder (only `javafx.base` is used by the server), org.json, and MySQL Connector/J for connected mode. The launcher detects the existing machine's JDK/JavaFX and Maven-cache JAR locations; override paths when needed:

```powershell
./start-web.ps1 -Demo -Jdk 'C:\path\jdk-25' -JavaFx 'C:\path\javafx-sdk\lib' -JsonJar 'C:\path\json-20240303.jar'
```

Pass `-MySqlJar` for a different MySQL driver location. Install frontend dependencies with `npm ci --prefix web` (the first launcher run also does this if needed). No frontend environment variable contains a secret.

## Frontend development

Run the Java API on port 8080, then `npm run dev --prefix web`. Vite serves the UI on port 5173 and proxies `/api` to the local Java server. Navigation uses URL hashes, so page refreshes work without special routing configuration.

`npm run build --prefix web` produces `web/dist`. This is a local, single-user application; its API is not intended to be exposed publicly.

## Behavior and limitations

- UI operations are asynchronous. Repeated submissions are disabled and server-side writes are serialized within this server process.
- Refreshes expose progress and per-symbol failures. A saved trade stays reported as saved even when a following reload fails. Failed reloads keep the previous portfolio visible.
- Charts are selected-stock daily price history, not historical portfolio value. Connected history uses the existing provider; demo history is explicitly sample data.
- Daily history is cached for 15 minutes in the server and browser session. Concurrent requests for the same stock share a provider call; navigation and browser reloads reuse cached data. History up to 24 hours old stays visible while updating, with a saved-data label if refreshing fails. Uncached requests return HTTP 202 while loading; the browser polls automatically until the provider completes. The shared HTTP client reuses connections and applies a 12-second overall request timeout. Actual provider failures have a 30-second retry cooldown. New symbols still depend on provider availability.
- Portfolio briefs summarize current holdings. They are not market news or investment recommendations.
- Returns are unrealized and exclude fees, dividends, and closed positions. The existing average-cost accounting is retained.
- Watchlists and theme preference are saved in this browser, separately for demo and connected mode. They are not synchronized across devices.
- This does not add cross-process database locking or a historical transaction ledger. Avoid simultaneous editing through other clients.

## Verification

With the demo server running:

```powershell
npm test --prefix web
```

Browser tests use installed Google Chrome; set `PLAYWRIGHT_BROWSER_CHANNEL=msedge` to use Edge, or install Playwright Chromium and set the variable to `chromium`. Set `FOLIO_TEST_URL` if using a different port. Tests use demo data and isolated browser fixtures; they must not be pointed at a real portfolio. Screenshots and reports go into ignored `web/test-results`.

The existing Java regression checks are documented in `tests/README.md`.
