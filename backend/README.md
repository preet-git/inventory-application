# Inventory Import API

Java 25 · Spring Boot 4.1 · PostgreSQL 18 · Apache POI 5.5 · Flyway

This service:

- receives product files (`.xlsx`, `.xls`, `.csv`) and validates every row;
- stores the valid rows in PostgreSQL and records the rejected ones;
- serves the dashboard's product table (sorted and cursor-paged) and its summary.

Imports run in the background. The upload returns immediately with an import id, and the caller
polls that id for progress, so a large file never holds an HTTP request open.

---

## Running

### With Docker Compose (recommended)

From the repository root:

```bash
docker compose up --build -d      # API on http://localhost:8080, dashboard on http://localhost:8081
```

Compose supplies the database and every setting. See the [root README](../README.md).

### From an IDE or terminal, using the Docker database

This needs JDK 25; the Maven wrapper downloads Maven itself. Start only PostgreSQL, then run the app
with `DB_PORT=5433`:

```bash
# from the repository root
docker compose up -d db              # PostgreSQL on localhost:5433
docker compose stop backend          # only if the API container is running: both need port 8080

cd backend
DB_PORT=5433 ./mvnw spring-boot:run  # macOS / Linux
```

```powershell
$env:DB_PORT = "5433"; .\mvnw.cmd spring-boot:run     # Windows PowerShell
```

In IntelliJ, open *Run → Edit Configurations* for `InventoryApplication` and set `DB_PORT=5433`
under Environment variables. The default database name, user and password (`inventory` / `postgres`
/ `postgres`) already match the Docker database.

### Against your own PostgreSQL

Set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` and `DB_PASSWORD`. The database must already exist;
Flyway creates the tables on first start.

PostgreSQL is required. Flyway owns the schema and Hibernate only validates it
(`ddl-auto: validate`), so the app will not start without a database it can migrate.

---

## Configuration

### Environment variables

All are optional.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_HOST` | `localhost` | PostgreSQL host. Compose sets `db`, the database's service name |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `inventory` | Database name |
| `DB_USER` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password. Fine locally; set a real one anywhere shared |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:8081` | Browser origins allowed to call `/api/**`, comma-separated. Compose passes its own list |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM flags (container only) |

### Upload limits (`application.yml`)

| Setting | Value | Why |
| --- | --- | --- |
| `spring.servlet.multipart.max-file-size` | `50MB` | The largest file accepted |
| `spring.servlet.multipart.max-request-size` | `51MB` | The file plus the multipart framing |
| `spring.servlet.multipart.file-size-threshold` | `0B` | Uploads stream to a temp file instead of being held in memory |
| `server.tomcat.max-swallow-size` | `-1` | An over-limit upload is still read to the end, so the client receives the 400 error instead of a dropped connection |

There is no row limit; file size is the only cap.

---

## API

Base path is `/api`. Everything is JSON except the upload, which is `multipart/form-data` with one
field, `file`.

| Method | Path | Returns |
| --- | --- | --- |
| `POST` | `/api/imports` | **202** and the new import (status `PENDING`) |
| `GET` | `/api/imports/{importId}` | The import's status and counters. Poll until `COMPLETED` or `FAILED` |
| `GET` | `/api/imports/{importId}/rejections?page=0&size=100` | Rejected row numbers, ascending. `size` is limited to 1–1000 |
| `GET` | `/api/products?cursor=&size=20&sortBy=productSku&direction=ASC` | One page of the product table. `size` is limited to 1–200 |
| `GET` | `/api/products/summary` | Totals for the summary panel |

```bash
curl -F "file=@products.xlsx" http://localhost:8080/api/imports
curl http://localhost:8080/api/imports/1
curl http://localhost:8080/api/products/summary
curl "http://localhost:8080/api/products?size=20&sortBy=stockAgeDays&direction=DESC"
curl "http://localhost:8080/api/imports/1/rejections?page=0&size=100"
```

A Postman collection with the same requests is in [`postman/`](postman/).

### Import status

```json
{
  "importId": 1,
  "fileName": "products.xlsx",
  "status": "COMPLETED",
  "summary": {
    "rowsRead": 120,
    "importedCount": 117,
    "rejectedCount": 3,
    "startedAt": "2026-09-10T10:15:02.114Z",
    "finishedAt": "2026-09-10T10:15:02.980Z",
    "failureMessage": null
  },
  "rejectedRows": { "count": 3, "rowNumbers": [14, 57, 90], "truncated": false }
}
```

- **`status`** goes `PENDING → RUNNING → COMPLETED`, or ends in `FAILED` with `failureMessage` set.
- **The counters** climb while the import runs, because progress is saved after every chunk of
  1,000 rows.
- **`rejectedRows`** includes the first 100 row numbers and sets `truncated` when there are more.
  The rest come from `/rejections`.

### Product page

```json
{
  "content": [
    {
      "rowNumber": 2,
      "productSku": "SKU-1001",
      "productName": "Wireless Mouse",
      "category": "Electronics",
      "purchaseDate": "2025-11-03",
      "unitPrice": 24.99,
      "quantity": 40,
      "lineValue": 999.60,
      "stockAgeDays": 311
    }
  ],
  "size": 20,
  "sortBy": "productSku",
  "direction": "ASC",
  "nextCursor": "U0tVLTEwMjB8MjA",
  "hasMore": true
}
```

- **Row fields.** `rowNumber` is the row in the file the product came from. `lineValue` is unit
  price × quantity. `stockAgeDays` is the number of days from the purchase date to today.
- **`sortBy`** accepts `rowNumber`, `productSku`, `productName`, `category`, `purchaseDate`,
  `unitPrice`, `quantity`, `lineValue` or `stockAgeDays`. `direction` is `ASC` or `DESC`.
- **Paging is by cursor (keyset), not page number.** Pass the previous page's `nextCursor`, and
  stop when `hasMore` is `false`.
- **A cursor belongs to its sort.** Start without one after changing `sortBy` or `direction`.
- **There is no total in the response.** Use `totalProducts` from the summary.

### Summary

```json
{ "totalProducts": 117, "totalInventoryValue": 48213.75, "averageStockAgeDays": 186.42 }
```

These are computed across the whole `product` table: the row count, the sum of unit price ×
quantity, and the average of (today − purchase date) in days.

### Errors

Every error has the same shape, with a message written for a person:

```json
{ "code": "unsupported_file_type", "message": "'notes.txt' is not a supported file. Upload a .csv, .xls or .xlsx file." }
```

| Status | `code` | When |
| --- | --- | --- |
| 400 | `invalid_file` | Empty upload; contents don't match the extension; over 50 MB |
| 400 | `invalid_request` | Unknown `sortBy` or `direction`, or a malformed `cursor`. The message lists the valid values |
| 400 | `missing_parameter` | No `file` part in the upload |
| 404 | `not_found` | No import with that id |
| 415 | `unsupported_file_type` | Not a `.csv`, `.xls` or `.xlsx` file |
| 429 | `import_queue_full` | Two imports running and ten queued. Try again shortly |
| 500 | `internal_error` | Anything unexpected. Details go to the log, not the response |

Problems found while reading the file arrive later, as a `FAILED` import with a `failureMessage`.
These include a missing header row, bad encoding and uncalculated formulas. Problems with
individual rows become rejections.

---

## How an import works

**On the upload request**

1. An empty upload is refused.
2. The file is copied to a temp file, and its type is checked twice:
  - first by its extension;
  - then by its first bytes. A `.xlsx` must be a ZIP-based Office file, an `.xls` must be an OLE2
    file, and a `.csv` must be neither.

   A renamed file is refused here, before any parser sees it.
3. An `import_run` row is created with status `PENDING`. The work goes to a pool of 2 worker
   threads with a queue of 10, and the response is `202`.

**On the worker**

4. The file is opened with the matching reader:
  - **`.csv`**: Apache Commons CSV, in strict UTF-8 (a byte-order mark is fine).
  - **`.xlsx`**: excel-streaming-reader. It keeps a window of 256 rows in memory and spills Excel's
    shared-string table to disk, so memory stays flat whatever the file size.
  - **`.xls`**: loaded whole by Apache POI so its formulas can be evaluated. The format holds at
    most 65,536 rows, which keeps these files small.
5. The header row is located within the first 20 non-blank rows. Matching is case-insensitive and
   in any order; in a workbook, the first sheet that has a header is used. Without one, the import
   fails and the message lists the missing columns.
6. Each row is validated. Every broken rule is recorded, not just the first, so a file can be fixed
   in one pass.
7. Valid rows are checked for a repeated SKU + Purchase Date within the file.
8. Every 1,000 accepted rows, one transaction writes the chunk:
  - rows whose SKU + date already exist are updated;
  - the rest are inserted;
  - rejections and progress counters are saved alongside.
9. The run ends `COMPLETED` with its totals, or `FAILED` with a message. The temp file is deleted
   either way.

If the application stops mid-import, the next start marks any `PENDING` or `RUNNING` run as
`FAILED`. Chunks that were already committed stay, and the message says so.

### Validation rules

| Field | Rule | Example rejection reason |
| --- | --- | --- |
| Product SKU | Required, at most 64 characters; trimmed and upper-cased | `Product SKU is required` |
| Product Name, Category | Required, at most 255 characters; trimmed | `Category exceeds 255 characters` |
| Purchase Date | Required; one of the formats below, or an Excel date serial | `Purchase Date '31/31/2024' is not a recognisable date` |
| Unit Price | Required, zero or more; currency symbol and `1,234.56`-style grouping allowed; rounded to 2 decimals | `Unit Price must not be negative, but was -5` |
| Quantity | Required whole number above zero | `Quantity '2.5' must be a whole number` |

**Date formats.** These are tried in order, so month-first wins when both readings are valid:

- `yyyy-M-d`, `M/d/yyyy`, `d/M/yyyy`, `yyyy/M/d`, `M-d-yyyy`, `d-M-yyyy`
- `d MMMM yyyy`, `MMMM d, yyyy`, `MMMM d yyyy`, `d MMM yyyy`, `MMM d, yyyy`, `MMM d yyyy`

A time after an ISO date (`2024-03-15T10:30:00`) is dropped. Real date cells in Excel are read as
dates whatever their display format.

### Duplicates

- **In the database:** `UNIQUE (product_sku, purchase_date)`, named `product_unique_sku_date`,
  guarantees one row per pair.
- **Within one file:** the first occurrence is imported. Later ones are rejected with
  *Duplicate Product SKU and Purchase Date in this file*. Removing them before the write also keeps
  a single batch from touching one row twice.
- **Across imports:** a pair that already exists is updated in place with the new file's values.
  Its `import_id` and `source_row_number` then point to the new file. Re-importing a corrected file
  therefore fixes rows instead of failing on them.

Because SKUs are upper-cased first, `abc-1` and `ABC-1` count as the same SKU.

### Formulas

| File | How formula cells are read | When the row is rejected |
| --- | --- | --- |
| `.xls` | Evaluated by POI's `FormulaEvaluator`. References to other workbooks fall back to the value Excel last saved | The formula evaluates to an error (`#DIV/0!`, `#REF!`, …) or POI cannot evaluate it |
| `.xlsx` | The result Excel saved with the file. A streaming reader cannot evaluate formulas, and Excel always stores the result | The saved result is an error, or the cell was never calculated |
| `.csv` | Not applicable: a cell like `=A1*2` is plain text | It fails the field's validation |

The rejection reason names the column and the formula, for example
`Unit Price formula '=B2/0' could not be evaluated: #DIV/0!`.

If *every* data row in an `.xlsx` is unreadable because its formulas were never calculated, the
import fails. This is typical of files generated by other tools, and the message says to open the
file in Excel, save it, and upload it again.

---

## Database schema

Flyway migrations in `src/main/resources/db/migration` run at start-up.

| Table | Holds |
| --- | --- |
| `import_run` | One row per upload: file name, status, start and finish times, the date it ran, progress counters, failure message |
| `product` | The imported rows. Unique on `(product_sku, purchase_date)`; checks `unit_price >= 0` and `quantity > 0`. `import_id` and `source_row_number` record the file and row that last wrote it |
| `import_rejection` | One row per rejected row: `(import_id, row_number)` and the reason, up to 500 characters |

Two indexes serve sorting:

- The unique constraint serves SKU order.
- `(purchase_date, id)` serves both date order and stock-age order, since stock age is just the
  date reversed.

`V2` makes the product id sequence step by 1,000 so Hibernate can batch inserts. That step must stay
equal to `allocationSize` on `ProductEntity`.

To open a SQL prompt on the Docker database:

```bash
docker compose exec db psql -U postgres -d inventory
```

---

## Docker image

The `Dockerfile` has two stages:

- **Build** on `eclipse-temurin:25-jdk`. The Maven wrapper downloads dependencies (cached between
  builds) and packages the jar. The jar is split into layers, so a code-only change rebuilds only a
  small layer.
- **Run** on `eclipse-temurin:25-jre-alpine`, as a non-root user. The heap is 75% of the container's
  memory limit (`mem_limit: 1g` in compose). An out-of-memory error exits the container, so the
  restart policy can bring it back.

The health check calls `GET /api/products?size=1`, which needs the database too. It allows 60
seconds on first boot while the migrations run.

---

## Project layout

```
src/main/java/com/ablsoft/inventory/
├── web/          controller and the single exception handler
├── service/      import orchestration and chunked writes
├── read/         file-type detection, header detection, CSV and Excel readers
├── validate/     row rules and date parsing
├── repository/   Spring Data repositories, plus JDBC for paging and the summary
├── entity/       JPA entities
├── model/        API response records
├── config/       CORS and the import thread pool
└── exception/    ImportException, mapped to HTTP status codes
src/main/resources/
├── application.yml
└── db/migration/ Flyway migrations
```

## Known gaps

- **No tests.** `src/test` doesn't exist, so `-DskipTests` in the Dockerfile skips nothing.
- **Rejection reasons aren't in the API.** They are stored in `import_rejection.reason`, but the
  endpoints return row numbers only.
- **Future purchase dates are accepted,** which gives a negative stock age.
- **Very large prices fail the import.** Validation has no upper bound, but the column is
  `numeric(12,2)`. A unit price of 10,000,000,000 or more makes its 1,000-row chunk fail to write,
  which ends the import as `FAILED` (earlier chunks stay).
- **"Today" is the server's date.** The container runs in UTC, so near midnight stock ages can be a
  day off for users far from UTC.
- **Nothing prunes `import_run` or `import_rejection`,** and no endpoint lists past imports.
- **A restart mid-import leaves committed chunks in place,** as described above.