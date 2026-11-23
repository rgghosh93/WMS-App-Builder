# WMS App Builder

Base WMS (Java/Spring Boot backend, React frontend) that the App Builder will extend.
Requirements: see the "WMS App Builder — Product Requirements Document" doc.

## Layout

| Folder | What |
| --- | --- |
| `backend/` | Spring Boot 4 (Java 21) API. Page directory metadata lives in `src/main/resources/catalog/pages.json`. |
| `frontend/` | React 18 + TypeScript + Vite. Dev server proxies `/api` to `localhost:8080`. |

## Run locally

Backend (port 8080):

```bash
cd backend && ./mvnw spring-boot:run
```

Frontend (port 5173):

```bash
cd frontend && npm install && npm run dev
```

Then open http://localhost:5173.

The mobile device app is at http://localhost:5173/rf (or Picking > Mobile Device). With demo data
on, sign in as `PICKER1` or `PICKER2`; the PIN is `wms.demo-data.user-pin` in
`backend/src/main/resources/application.properties`. Barcode scanners work as keyboards that end
each scan with Enter.

The first time the desktop is opened on the computer the backend runs on, it asks for the first
administrator; after that everyone signs in with a user ID and password. Users, their PINs and passwords,
and roles are managed under Administration. If nobody knows an administrator password any more, start the
backend once with `wms.users.allow-setup=true` and open the desktop on that computer to make another.
Use HTTPS when the WMS is reached over a network: without it passwords and PINs travel unencrypted.

The pack station app is at http://localhost:5173/pack (or Pack and Ship > Pack Station), with the
same sign-in. It asks which station it stands at once and remembers it in that browser.

Loading and ship confirm are on the mobile device (Loading). Carton labels, packing slips and
manifests print from the browser; labels are sized for 4 x 6 inch stock.

## Database

Local dev uses an embedded H2 database in PostgreSQL mode, saved under `backend/data/`
(git-ignored). Flyway migrations in `backend/src/main/resources/db/migration` own the schema.
On first start with an empty database, 144 demo storage locations, 4 pack stations, 3 dock doors and 2 receiving areas are loaded, with put-away zones and rules
(`wms.demo-data.enabled=true`). Delete `backend/data/` to start fresh.

To run against PostgreSQL instead, start the backend with the `postgres` profile and set
`WMS_DB_URL`, `WMS_DB_USER`, `WMS_DB_PASSWORD`:

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

## Tests

```bash
cd backend && ./mvnw test
```

The same backend tests against a real PostgreSQL 17, started for the run with no Docker or install needed:

```bash
cd backend && ./mvnw test -Dwms.test.postgres=true
```

```bash
cd frontend && npm test
```

## Adding a page to the directory

Add an entry to the right module in `backend/src/main/resources/catalog/pages.json`
with a unique `id` and `route`. Set `"status": "AVAILABLE"` once the page is built;
`PLANNED` pages show as "Coming soon" on the home screen and in the sidebar.

## Deploying

The WMS deploys to Render as two environments, base (`main`) and builder (`builder`). See [DEPLOY.md](DEPLOY.md).
