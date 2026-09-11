# Inventory Dashboard

An Angular 21 single-page dashboard for the inventory API. It has:

- a sortable, paginated product table with stock age;
- a summary panel;
- an **Import Data** dialog that uploads a file and follows the import live.

---

## Running

### With Docker Compose (recommended)

From the repository root:

```bash
docker compose up --build -d     # dashboard on http://localhost:8081
```

The image builds the app with Node inside Docker and serves the result with nginx, so Node isn't
needed on your machine. See the [root README](../README.md).

### Development server (live reload)

This needs Node 20.19+, 22.12+ or 24+ (`.nvmrc` pins 24.20.0), and the API running on port 8080:

```bash
# from the repository root: database and API in Docker
docker compose up -d db backend

cd frontend
npm ci          # installs exactly what package-lock.json lists
npm start       # http://localhost:4200
```

`http://localhost:4200` is in the API's allowed origins, so the dev server can call it.
`npm run build` writes a production build to `dist/frontend/browser`.

---

## How it reaches the API

The address is set in `src/app/core/api-url.ts`: `http://localhost:8080/api`. The browser calls it
directly, with no proxy in between, so:

- the API must be published on host port **8080**, and
- the address the dashboard is opened from must be in the API's `CORS_ALLOWED_ORIGINS`.

Every request goes through `src/app/core/inventory-api.ts`. The API's `{code, message}` error
bodies are shown to the user as they are. A request that never reaches the server shows
*Cannot reach the API. Check that the backend is running and reachable.*

---

## What's on the page

**Header.** The title and a large **Import Data** button.

**Products table.**

- **Columns:**
    - Row (the row in the source file)
    - Product SKU, Product Name, Category
    - Purchase Date
    - Unit Price, Quantity
    - Line Value (unit price × quantity)
    - Stock Age (Days)
- **Sorting.** Clicking a header sorts on the server, across every row rather than just the
  visible page. A new column sorts ascending, except Stock Age, which starts with the oldest.
- **Paging.** 10, 20, 50 or 100 rows per page, with Previous and Next.

**Summary panel.**

- *Successfully imported*: products stored, total inventory value and average stock age, plus the
  last import's file name and counts.
- *Rejected rows*: the row numbers the last import rejected (the first 100).

**Import dialog.**

1. You choose or drag in a `.xlsx`, `.xls` or `.csv` file. The extension is checked instantly, and
   the server re-checks the file's contents.
2. After upload, the dialog polls the import every second, showing rows read, imported and rejected
   as they climb.
3. It ends on the result, with *Import another* or *Done*.
4. The table and summary reload after each import.

---

## Design notes

- **Paging is by cursor.** The API returns a `nextCursor` instead of page numbers, and no total.
    - The store keeps the cursor that opened each visited page, so Previous re-fetches the page
      before.
    - The "1-20 of 1,204" total comes from the summary endpoint.
- **Stock age and line value come from the API.** Recomputing age in the browser would mean
  parsing dates in the user's timezone, which could disagree with the summary by a day.
- **An import is a job, not a request.** The upload returns an id straight away, and the dialog
  polls until the status is `COMPLETED` or `FAILED`.
- **Angular defaults, nothing extra.**
    - Standalone components, signals and `OnPush`.
    - No router (one page) and no state library.
    - No custom nginx config: with no client-side routes, the stock nginx image serves the build
      as-is.
- **Layout.** One CSS grid, with `minmax(0, 7fr)` for the table and `minmax(0, 3fr)` for the panel.
  It stacks to one column below 1100px. The `minmax(0, …)` lets the wide table scroll inside its
  column instead of pushing past it.

## Theme

ABLSoft's palette, taken from ablsoft.com:

- teal `#00BACB` as the primary colour;
- navy `#263744` for text;
- green `#29C229` as the success accent;
- Roboto for text and Roboto Slab for headings.

All tokens are in `src/styles.css`. Components use roles such as `--accent` and `--danger` rather
than literal colours.

---

## Project layout

| Path | Holds |
| --- | --- |
| `src/app/core/api-url.ts` | The API's address |
| `src/app/core/inventory-api.ts` | Every HTTP call, and the one place error bodies are unwrapped |
| `src/app/core/products-store.ts` | Sort, page size, page position and the cursor stack behind Previous |
| `src/app/core/models.ts` | The API's JSON shapes |
| `src/app/core/iso-date.pipe.ts` | `YYYY-MM-DD` formatting that doesn't shift a day across timezones |
| `src/app/dashboard/` | The page: header, table section, summary panel and the last import |
| `src/app/products-table/` | The table and its sort headers |
| `src/app/summary-panel/` | The right-hand column: totals, last import, rejected rows |
| `src/app/import-dialog/` | Upload and progress |
| `src/styles.css` | Global styles and theme tokens |

## Docker image

The `Dockerfile` has two stages:

- **Build** on `node:24-alpine`. `npm ci` installs exactly what `package-lock.json` lists, then
  `ng build` produces the production bundle.
- **Run** on `nginx:1.29-alpine`, serving `dist/frontend/browser` as static files.

`.dockerignore` keeps any local `node_modules`, `dist` and `.angular` out of the build. The image
therefore never depends on what's installed on the machine building it.

## Known gaps

- **No tests.** There are no spec files.
- **The API address is compiled in.** Serving the API anywhere other than `http://localhost:8080`
  means editing `api-url.ts` and rebuilding.
- **nginx is not in the upload path.** If `/api` is ever proxied through it, raise nginx's
  `client_max_body_size` (1 MB by default) to at least `51m`, or uploads over 1 MB will fail.