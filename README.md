# Folio

Folio is a multi-user stock portfolio tracker built with React and Java. Record trades, follow companies across markets, review allocation, and explore portfolio return scenarios.

[Open Folio](https://portfolio-manager-gui.vercel.app/) · [Deployment guide](deploy/README.md) · [Java regression checks](tests/README.md)

## Features

- **Portfolio workspace** — Overview, Holdings, Stock Library, Watchlist, Settings, and individual stock pages with cost basis, saved valuation, unrealized returns, and allocation.
- **Stock discovery** — a searchable starter catalog of 38 companies across US, Bombay, and Toronto listings, market and ownership filters, and account-specific browser watchlists.
- **Currency display** — USD, INR, EUR, GBP, CAD, and JPY using dated exchange rates.
- **Price history** — daily closing-price charts, provider fallback, cached results, and visible refresh or failure states.
- **Portfolio analysis** — concentration review, suggested next steps, optional Gemini summaries, and adjustable return scenarios over 1, 3, 5, or 10 years.
- **Authentication** — Firebase email/password signup, verification and password reset, Google OAuth, and optional email-code flows through Resend. Available methods depend on server configuration.
- **Session management** — concurrent device sessions, a 15-minute inactivity timeout, and shared activity tracking across tabs using the same session.
- **Responsive interface** — light and dark themes, an animated public homepage, rotating example charts, and interactive portfolio examples. Motion respects reduced-motion preferences.

Folio records portfolio entries. Orders are executed through your brokerage.

## Architecture

| Component | Implementation |
| --- | --- |
| Frontend | React 19, Vite, DM Sans, Lucide icons |
| API | Java 25, JDK HTTP server, JDBC |
| Hosted database | Neon PostgreSQL with account-scoped records |
| Local database | MySQL with the existing local schema |
| Deployment | Vercel frontend, Docker-based Java service on Render |
| Market data | Finnhub, Alpha Vantage, optional Twelve Data fallback |
| Analysis | Optional Gemini generation with measured-data fallbacks |
| Verification | Playwright browser checks and Java regression runners |

The browser calls same-origin `/api/*` endpoints. Vercel forwards these requests to Render, where Java validates the session and accesses the authenticated account's records in PostgreSQL. Database credentials and provider secrets belong in the backend environment.

Hosted trades use database transactions and account-row locking to coordinate concurrent updates. Local mode uses the original single-user database. Local holdings require an explicit import to appear in a hosted account.

## Loading and reliability

- Login and signup pages preload the dashboard module. After authentication, the app reuses that module and initializes the workspace from one portfolio snapshot.
- Daily history has a six-hour server freshness window and a 15-minute browser freshness window. Results up to 24 hours old can remain visible while a refresh runs.
- Concurrent history requests for one symbol share a provider call. Server history work uses three workers, a bounded queue, and a 128-symbol cache.
- Uncached history can return HTTP 202 while loading. The browser polls for completion, and failed provider requests have a 30-second cooldown.
- Read requests retry temporary gateway failures with bounded backoff within the request timeout. Writes require explicit user action to retry.
- Price refreshes report progress and per-symbol failures. A successful trade remains reported as saved if its subsequent reload fails.
- Backend startup can delay API availability. The interface displays a connection message while waiting and provides a retry action after failure.

## Run locally

### Prerequisites

Install Node.js 22.12 or newer, JDK 25, the JavaFX 25 SDK, and org.json. Connected local mode also needs MySQL Connector/J and a configured database. The Java server uses JavaFX collection classes.

From the repository root in PowerShell:

```powershell
npm ci --prefix web
./start-web.ps1 -Demo
```

Open [localhost on port 8080](http://127.0.0.1:8080). Demo mode uses in-memory holdings and generated history. Restarting the server resets demo trades.

For a local MySQL portfolio, configure environment variables or an ignored `src/config.properties` using [the configuration example](src/config.properties.example), then run:

```powershell
./start-web.ps1
```

Connected local mode requires the existing `stocks` and `portfolio` tables. Stop the server with Ctrl+C. Use `-Port 8081` to change the port or `-SkipBuild` to reuse a previously built web bundle.

Override dependency paths if the launcher cannot detect them:

```powershell
./start-web.ps1 -Demo -Jdk 'C:\path\jdk-25' -JavaFx 'C:\path\javafx-sdk\lib' -JsonJar 'C:\path\json-20240303.jar'
```

Use `-MySqlJar` to specify the MySQL driver.

### IntelliJ

Run `Main` to build the frontend, start the local web server, and open the browser. Node.js/npm must be available to the IDE. Supported arguments include `--demo`, `--no-open`, `--skip-build`, and `--port 8081`. The launcher chooses another available port when the default is occupied.

Run `LegacyDesktopApp` explicitly to open the earlier JavaFX interface.

### Frontend development

Start the Java API on port 8080, then run:

```powershell
npm run dev --prefix web
```

Vite serves the frontend on port 5173 and proxies API requests to Java. Hash-based navigation supports page refreshes without route-specific server configuration.

```powershell
npm run build --prefix web
```

The production frontend is written to `web/dist`.

## Hosted deployment

Follow [deploy/README.md](deploy/README.md) for Neon, Render, Vercel, Firebase, Google OAuth, Resend, and optional Gemini configuration.

1. Create the PostgreSQL database and configure backend credentials.
2. Deploy the repository's Dockerfile to Render using `render.yaml` or a web service.
3. Set `FOLIO_PUBLIC_ORIGIN` to the exact frontend origin and enable hosted schema initialization.
4. Point Vercel's API rewrites at the deployed Render service.
5. Verify registration, sign-in, and portfolio isolation using two accounts.

The Docker build uses Maven to package the hosted Java server and its dependencies. `WebServer --hosted` binds to the hosting platform's port.

`GET /api/health` verifies that Java is reachable. A successful authenticated snapshot also checks the account and database path.

## Verification

With a disposable demo server running:

```powershell
npm test --prefix web
```

To run the authentication and startup browser suite against a local production preview:

```powershell
npm run build --prefix web
$env:FOLIO_TEST_URL = 'http://127.0.0.1:4873'
$env:FOLIO_AUTH_PREVIEW = '1'
npm test --prefix web -- auth.spec.js
```

This suite covers registration, failed login, session expiry, account changes across tabs, dashboard preloading, gateway recovery, and chart rotation using mocked API responses.

Playwright uses installed Google Chrome by default. Set `PLAYWRIGHT_BROWSER_CHANNEL=msedge` for Edge, or install Playwright Chromium and select `chromium`. Screenshots and traces are written under ignored `web/test-results`. Run tests against disposable data.

See [tests/README.md](tests/README.md) for Java checks covering provider failures, partial updates, history caching, account isolation, sessions, and concurrent sales. Hosted SQL checks use an in-memory H2 database in PostgreSQL compatibility mode. The deployment guide includes live database smoke checks.

## Data semantics

- Charts show individual-stock daily closing prices. Homepage examples use illustrative prices.
- Saved portfolio values use USD as their accounting basis. Display conversions use dated reference rates. International quote availability depends on provider coverage.
- Unrealized returns exclude fees, dividends, and closed positions.
- Projections use an assumed constant annual return. They exclude contributions, withdrawals, fees, and taxes.
- Analysis confidence describes the evidence available for an allocation review. It does not estimate the probability of investment gains.
- Gemini receives public price statistics for stock summaries and aggregate portfolio facts for allocation analysis when configured and requested.
- Watchlists are stored per account in the current browser. Theme preferences are stored per browser origin.
- Hosted accounts and holdings persist in PostgreSQL. In-memory caches and refresh jobs reset when the backend restarts.
