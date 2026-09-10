# Inventory dashboard

Angular 21 front end for the product inventory import API. One page: a summary card, a sortable
paginated table of every imported product, and an **Import Data** button that uploads a
spreadsheet and follows the import to its result.

The API it talks to lives in its own repository (`inventory-app`); this repository is the browser
half only.

## Running it

Node is pinned in `.nvmrc`. Angular 21 requires 20.19+, 22.12+ or 24+ and refuses anything older,
so start by matching it:

```bash
nvm use          # reads .nvmrc -> Node 24.20.0
npm install
```

Then bring up the API from the `inventory-app` repository, and serve this project against it:

```bash
docker compose up -d      # in inventory-app: database + API on :8080
npm start                 # here: dev server on http://localhost:4200
```

The dev server calls `http://localhost:8080/api` by default. The API must allow this origin —
`CORS_ALLOWED_ORIGINS` on the backend already lists `http://localhost:4200`.

To run the built image instead of the dev server:

```bash
docker compose up --build     # dashboard on http://localhost:8081
```

That serves the production bundle through nginx and reads the API URL from `API_BASE_URL`, so it
needs the backend running separately on :8080.

```bash
npm test        # vitest, via the Angular unit-test builder
npm run build   # production bundle into dist/
```

## How it is put together

| Path | What it holds |
| --- | --- |
| `src/app/core/inventory-api.ts` | Every HTTP call, and the one place `{code, message}` error bodies are unwrapped |
| `src/app/core/products-store.ts` | Sort, page position and the cursor stack that makes Prev work |
| `src/app/core/models.ts` | The API's shapes, mirrored one-for-one |
| `src/app/core/iso-date.pipe.ts` | `YYYY-MM-DD` formatting that does not drift a day across timezones |
| `src/app/dashboard/` | The page: owns the store, wires the pieces together |
| `src/app/products-table/` | The table and its sort headers. Presentational |
| `src/app/summary-cards/` | The three summary figures. Presentational |
| `src/app/import-dialog/` | Upload, poll, and the outcome including rejected rows |

Standalone components, signals, `OnPush`, and no zone.js — Angular 21 is zoneless by default and
nothing here needs otherwise.

## Three decisions worth knowing about

**Paging is by cursor, so pages are walked rather than jumped to.** The API pages by opaque cursor
and returns no total count, because counting a large table on every page request is exactly what
keyset paging exists to avoid. A cursor only says where the *next* page begins, so going back is
not a subtraction: the store keeps the cursor that opened each visited page and Prev re-fetches
with the one beneath. The visible consequence is that there is no jump-to-page-7 control. The
"1-20 of 120" total comes from the summary endpoint, which does count.

**Stock age and line value are shown, not computed.** The API already derives both, so the client
displays what it is given. Recomputing age in the browser would mean parsing a date into the user's
timezone and disagreeing with the summary card by a day for anyone west of Greenwich.

**The import is a job, not a request.** The upload returns a run id immediately and the work
happens on a worker, so the dialog polls the status endpoint once a second and shows the counters
climbing. That is why the dialog stays open after the upload: the result — rows read, imported,
rejected, and which row numbers were rejected — only exists once the run finishes.

## Configuration

The API URL is decided when the container starts, not when the bundle is built: `public/env.js`
carries it, and the container's entrypoint rewrites that file from `API_BASE_URL` before nginx
starts. One image therefore serves any environment. The dev server uses the checked-in default of
`http://localhost:8080/api`.

Because the browser calls the API directly, the URL must be one the *user's machine* can reach —
the backend's published port, not the compose service name — and the API must allow the
dashboard's origin. `CORS_ALLOWED_ORIGINS` on the backend does that; the compose file here sets
only the URL, since the backend is started from its own repository.
