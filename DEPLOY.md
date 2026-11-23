# Deploying to Render

The WMS runs on [Render](https://render.com) as two environments, each a web service with its own PostgreSQL database.
Both are described in [`render.yaml`](render.yaml), so Render creates them in one go.

| Environment | Runs branch | What it is | Database |
| --- | --- | --- | --- |
| `wms-base` | `main` | The base WMS as shipped. Deploys once CI passes. | `wms-base-db`, empty |
| `wms-builder` | `builder` | Where the App Builder's changes go. | `wms-builder-db`, with the demo warehouse |

Each web service is one Docker image ([`Dockerfile`](Dockerfile)): the React app is built and packed into the Spring Boot
server, which serves both the screens and `/api` at one address. Database changes run by themselves when a service starts
(Flyway).

## How changes flow

```
you ──push──► main ──CI passes──► wms-base deploys
                │
                └─ Sync builder workflow: merge main into builder, test it on PostgreSQL
                       ├─ fine ─────► push builder ──► wms-builder deploys
                       └─ clash or tests fail ─► pull request "Bring base changes into builder"; nothing deploys
App Builder ──push──► builder ──► wms-builder deploys
approved builder change ──pull request──► main
```

- **CI** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs the frontend tests, the backend tests on H2 and
  again on a real PostgreSQL 17, and builds the Docker image, for every push and pull request.
- **Sync builder** ([`.github/workflows/sync-builder.yml`](.github/workflows/sync-builder.yml)) keeps `builder` up to date
  with `main`. The first time it runs, it creates `builder` as a copy of `main`.
- **Migrations on builder** are numbered from `V1000__…`, so they never clash with base ones (`V31__…` and on). The builder
  service allows base migrations that come later to be applied after them (`SPRING_FLYWAY_OUT_OF_ORDER=true`).
- **Starting builder over:** reset the `builder` branch to `main` and force-push it, then in Render delete and recreate
  `wms-builder-db` (or restore it from a backup). The demo warehouse loads again into the empty database.

## First-time setup

These steps are yours: they need your accounts and payment details.

1. **GitHub settings.** In the repository, Settings › Actions › General › Workflow permissions: choose
   *Read and write permissions* and tick *Allow GitHub Actions to create and approve pull requests*. The sync workflow
   needs both.
2. **Create the `builder` branch** by running the *Sync builder* workflow once (Actions › Sync builder › Run workflow), or
   by pushing to `main`. Render needs the branch to exist.
3. **Render account.** Sign up at render.com and connect your GitHub account. Choose a workspace plan: Hobby works for
   one person; Pro if others need to see the dashboard.
4. **Create the Blueprint.** New › Blueprint › choose this repository. Render reads `render.yaml` and shows two
   databases and two web services. It asks for one value:
   - `WMS_DEMO_PIN` (wms-builder): the PIN of the demo device users `PICKER1` and `PICKER2`, 4 to 12 digits.

   Apply it. The first deploy builds the image, which takes several minutes.
5. **Make the first administrator, for each environment.** Open the service's address (shown in Render, like
   `https://wms-base-xxxx.onrender.com`). The desktop asks for the first administrator and a **setup key**. The key is
   `WMS_SETUP_KEY` in the service's Environment tab in Render. It is needed only this once; after that, everyone signs in
   with a user ID and password. Add users under Administration.
6. **Devices** use the same address with `/rf`, and pack stations `/pack`.

## Costs

With the plans in `render.yaml`: two Standard web services (2 GB memory each, $25 a month each), a Basic 1 GB database
for base and a Basic 256 MB one for builder (about $20 and $6 a month), plus storage at about $0.30 per GB a month and
the workspace plan. Change `plan:` in `render.yaml` to resize. Check render.com/pricing for current prices.

## Settings

| Variable | Where | What |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | both | `postgres,cloud` |
| `WMS_DB_HOST`, `WMS_DB_PORT`, `WMS_DB_NAME`, `WMS_DB_USER`, `WMS_DB_PASSWORD` | both | From the service's database. `WMS_DB_URL` can be given instead of the parts. |
| `WMS_SETUP_KEY` | both | Generated. Lets the first administrator be made from a browser; at least 20 characters. |
| `WMS_DEMO_DATA` | builder | `true` loads the demo warehouse into an empty database. |
| `WMS_DEMO_PIN` | builder | PIN of the demo device users. |
| `SPRING_FLYWAY_OUT_OF_ORDER` | builder | `true`, for the builder's own migrations. |
| `WMS_DB_POOL` | both | Most database connections a service opens (10 unless set). |
| `PORT` | both | Set by Render. |

## Running the image yourself

```bash
docker build -t wms .
docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=postgres,cloud \
  -e WMS_DB_URL=jdbc:postgresql://host:5432/wms -e WMS_DB_USER=wms -e WMS_DB_PASSWORD=… \
  -e WMS_SETUP_KEY=a-long-random-key-of-20-or-more wms
```
