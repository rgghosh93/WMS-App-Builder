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

## Database

Local dev uses an embedded H2 database in PostgreSQL mode, saved under `backend/data/`
(git-ignored). Flyway migrations in `backend/src/main/resources/db/migration` own the schema.
On first start with an empty database, 144 demo storage locations are loaded
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

```bash
cd frontend && npm test
```

## Adding a page to the directory

Add an entry to the right module in `backend/src/main/resources/catalog/pages.json`
with a unique `id` and `route`. Set `"status": "AVAILABLE"` once the page is built;
`PLANNED` pages show as "Coming soon" on the home screen and in the sidebar.
