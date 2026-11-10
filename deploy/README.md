# Publish Folio with Neon, Render and Vercel

## 1. Open the Neon project

In the Folio project dashboard, click **Connect**. Note the host, database name, role and password. Keep credentials private. Choose a direct connection (pooling off) for this Java service. There is no need to run SQL, download a certificate or enable Neon Auth. Render initializes the schema at startup. Existing local MySQL holdings are not imported.

## 2. Put the tested source on GitHub

Render needs the repository version containing `Dockerfile`, `pom.xml`, `render.yaml`, `deploy/`, the hosted Java classes, and the updated React UI. Commit and push the reviewed changes to the branch you intend to deploy. Local `config.properties`, certificates, build output, and dependencies are ignored and must remain untracked.

## 3. Create the Render service

1. Sign in at https://dashboard.render.com/ and choose **New → Web Service**. Connect your GitHub repository and the intended branch.
2. Use the repository root (leave Root Directory empty), **Docker** runtime, **Singapore** region, and **Free** instance type. The Dockerfile is at the repository root. It builds both Java and the UI; no custom build/start commands are needed.
3. Set the health check path to `/api/health`.
4. Add these environment variables before deployment:

| Name | Value |
| --- | --- |
| `FOLIO_PUBLIC_ORIGIN` | `https://portfolio-manager-gui.vercel.app` (no trailing slash); replace if your Vercel domain differs |
| `FOLIO_INIT_SCHEMA` | `true` — creates the new hosted tables without changing the old local tables |
| `DB_URL` | `jdbc:postgresql://YOUR_NEON_HOST:5432/neondb` — use your actual database name; no credentials/query parameters in this URL |
| `DB_USER` | Neon role |
| `DB_PASSWORD` | Neon role password |
| `FINNHUB_API_KEY` | Your Finnhub key |
| `ALPHAVANTAGE_API_KEY` | Your Alpha Vantage key |
| `TWELVEDATA_API_KEY` | Optional server-side backup for daily charts. Use your Twelve Data key with access to the symbols you display. Add it in Render → Environment and redeploy. |

5. Deploy and wait for `/api/health` on the assigned `https://…onrender.com` URL to return `{"mode":"hosted"}`. A direct `/api/snapshot` request without a session must return HTTP 401. This is expected.

Alternatively, `render.yaml` can create the service as a Blueprint. You still need to supply the secrets in Render.

## 4. Connect Vercel to the real Render URL

From the repository root, replace the example below with your service's actual URL:

```powershell
node deploy/configure-vercel.mjs https://YOUR-SERVICE.onrender.com
```

This creates a root `vercel.json` and `web/vercel.json`. They forward `/api/*` to Render, preserving same-origin browser sessions. Both Vercel project root choices (repository root or `web`) are supported. Commit these configs and redeploy the Vercel project. Never set the backend URL to `localhost` or `127.0.0.1`.

The production Vercel origin must match `FOLIO_PUBLIC_ORIGIN` exactly. Preview deployment domains are deliberately not accepted for writes. Secrets belong only on Render, not in `VITE_*` variables or Vercel's frontend build.

## 5. Verify two separate accounts

1. Open the Vercel production site and create an account. It should show an empty portfolio with your username and a sign-out button.
2. Record a small test holding and reload. It should remain saved.
3. Sign out and create a second account. It must not see the first account's holdings or stock library.
4. Sign back into the first account and confirm the holding is still there. Remove the test position when finished.
5. Refresh prices and inspect a chart. These use the real API provider quotas; a provider failure must not be reported as a successful update.

The `/api/health` endpoint checks that the Java process is reachable, not database readiness. Registration and a successful snapshot verify the database path. Check Render's logs for startup failures; do not paste secrets into logs or chat.

## Existing holdings and operational limits

- Local data is not imported automatically. After creating your account, an explicit, reviewed import can copy your existing holdings into that account. Do not populate another account based on a browser-supplied owner ID.
- Accounts use usernames and passwords (12–128 characters), salted PBKDF2 hashes, and one active 24-hour session per account. Signing in again invalidates the earlier session. Password reset/email verification and account administration are not implemented yet; save your password.
- Registration is open. This setup is for a small project rollout, not a fully hardened public financial service. Authentication has a global limit of 20 attempts/minute per running server; it is not a distributed abuse-prevention service.
- Hosted trades lock the account row in a database transaction. The app still records holdings; it does not execute brokerage orders.
- Watchlists are stored per account in the current browser, not synced across devices. Theme preference is per browser origin.
- Render Free sleeps after inactivity. The first request can take about a minute; wait and retry if the initial connection times out. In-memory history caches and refresh jobs reset on restart; persisted accounts and holdings remain in Neon. If a refresh job disappears, reload saved prices before starting another refresh.

Provider references: [Neon Java guide](https://neon.com/docs/guides/java), [Render Docker](https://render.com/docs/docker), [Vercel rewrites](https://vercel.com/docs/routing/rewrites).
