# DBeaver Headless Export Command (`@exportnow`)

[![Build](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)
[![Nightly compatibility](../../actions/workflows/nightly.yml/badge.svg)](../../actions/workflows/nightly.yml)

A DBeaver plugin that fixes [dbeaver/dbeaver#19143](https://github.com/dbeaver/dbeaver/issues/19143):
the built-in `@export` command opens the data transfer wizard and stops the script after the
first export, so batch exports of multiple tables are impossible.

This plugin adds a new SQL control command **`@exportnow`** that:

- uses the same syntax as `@export`: the JSON holds the transfer settings, the **query follows
  as the next statement**
- runs the data transfer **headlessly** — no wizard, and the script continues
- runs each export as its own **background job** — multiple exports execute **in parallel**
- lets each export **fail independently**: a failed export shows an error notification and is
  logged, but the script and the other exports keep running
- still executes the query normally in the editor, so results appear in the **result grid**
  and the **Query Manager** history

## Usage

**Inline form** — the query is part of the statement; executing it (Ctrl+Enter or in a
script) starts the export immediately:

```sql
@exportnow {"type": "csv", "consumer": {"outputFolder": "/data/export", "outputFilePattern": "customers"}} SELECT * FROM customers;
```

For long queries use the multiline command block (`@@ … @@`):

```sql
@@exportnow {"type": "csv", "consumer": {"outputFolder": "/data/export", "outputFilePattern": "orders"}}
SELECT o.*
FROM orders o
@@
```

**Classic form** (like the built-in `@export`) — the JSON alone on its line; the **next
executed query** (any number of lines below) is exported:

```sql
@exportnow {"type": "csv", "consumer": {"outputFolder": "/data/export", "outputFilePattern": "orders"}};

SELECT * FROM orders;
SELECT 'script keeps going';
```

Note the mandatory space after `@exportnow`. Exports run in the background — the script
does not stop, multiple exports run in parallel, and a failing export doesn't affect the
others. Running exports appear as "Export data: …" jobs in the status-bar progress area
and the Background Tasks view, where they can be **cancelled**; cancelling shows an
"Export cancelled" notification.

### Execution semantics

Exports are **document-driven**: when an `@exportnow` command executes, the editor is
scanned for export blocks and each one runs headlessly (once per execution):

| How you execute | What exports |
|---|---|
| Alt+X (whole script) | all `@exportnow` blocks; queries also run normally in the grid |
| Ctrl+Enter / new tab, **no selection**, cursor on an `@exportnow` | **all** blocks in the document |
| Any execution with a **selection** | only blocks whose `@exportnow` is inside the selection |
| Selection or cursor on a plain statement | just executes — never an export |

The JSON accepts the same `type`, `producer`, `consumer` and `processor` sections as DBeaver's
built-in [`@export` command](https://dbeaver.com/docs/dbeaver/Export-Command/).

### Variables

`${name}` variables work in the **query** and in **every string of the JSON** (output folder,
file name pattern, processor options, …). They are resolved when the export starts:

| Variable | Value |
|---|---|
| `${user}` | user the DBeaver connection is logged in as (DB login) |
| `${user.name}` | operating-system user running DBeaver |
| `${host}`, `${port}`, `${server}`, `${database}`, `${url}` | connection settings |
| `${home}`, `${workspace}`, `${dbeaver_home}` | user home, DBeaver workspace, installation folder |
| `${MY_ENV_VAR}`, `${some.java.property}` | environment variables and Java system properties |
| `${current_date}` | today, `2026-10-12` |
| `${current_time}` | now, `14:30:05` |
| `${current_timestamp}` | now, `2026-10-12 14:30:05` |
| `${myvar}` | script variables set with `@set myvar = …` |

```sql
@set region = 'EU'

@exportnow {"type": "csv", "consumer": {"outputFolder": "/exports/${user.name}", "outputFilePattern": "orders_${user}"}}
SELECT * FROM orders WHERE region = ${region} AND created_by = '${user}';
```

- Values are inserted **as SQL text**: quote strings in the query (`'${user}'`) or in the value
  (`@set region = 'EU'`).
- The `current_*` variables are taken once per execution — all exports of a run get the same
  value — and work in queries (`WHERE created < DATE '${current_date}'`) and file names
  (`"outputFilePattern": "orders_${current_date}"`). `@set current_date = '2026-01-01'`
  overrides them, e.g. to re-run an old export. `${current_time}` contains `:`, which Windows
  doesn't allow in file names — use DBeaver's `${timestamp}` there.
- `@set` lines must come **above the first `@exportnow`** of the script — all exports of an
  execution are prepared when the first `@exportnow` runs.
- DBeaver's own file-name variables (`${table}`, `${timestamp}`, `${date}`, `${index}`,
  `${year}`, `${month}`, `${day}`, `${hour}`, `${minute}`, `${project}`, `${datasource}`, …)
  are left untouched and expanded by DBeaver as before.
- Any other `${name}` in the JSON without a value (e.g. `"outputFilePattern": "orders_${batch}"`)
  is **asked in the parameters dialog** together with the query parameters (see below).
- `${name:default}` in the JSON uses the default instead of asking (`${batch:nightly}`,
  `${suffix:''}` for empty), like everywhere in DBeaver.
- `${password}` is never substituted into the JSON (in a file name it ends up empty).

### Parameters

Parameters (`:name`, `${name}` without a value, anonymous `?`) are asked **before any export
starts** in DBeaver's own *Bind parameters* dialog — **once per execution for all exports**:

```sql
@exportnow {"type": "csv", "consumer": {"outputFilePattern": "orders_eu"}} SELECT * FROM orders WHERE region = :region AND year = :year;
@exportnow {"type": "csv", "consumer": {"outputFilePattern": "returns_eu"}} SELECT * FROM returns WHERE region = :region;
```

Executing this shows one dialog with `region` and `year` — a name used by several exports is
asked once and used everywhere. Unknown `${name}` variables of the JSON settings are asked in
the same dialog (shown as e.g. `outputFilePattern = ${batch}`); a name used both in a query and
in the JSON is asked once:

```sql
@exportnow {"type": "csv", "consumer": {"outputFolder": "/exports/${batch}", "outputFilePattern": "orders_${region}"}} SELECT * FROM orders WHERE region = :region;
```

Entering `'EU'` and `nightly` writes `/exports/nightly/orders_EU.csv` from
`… WHERE region = 'EU'`.

- Like in DBeaver's SQL editor, values are inserted into queries as SQL text — enter strings
  with quotes (`'EU'`), numbers as is (`2026`). In the JSON settings the value is used as plain
  text: enclosing quotes are removed, so `'EU'` and `EU` both give `EU` in a file name.
- Parameters named like a variable (`@set` or the table above) take its value and are not
  asked. DBeaver keeps `@set` variables for the connection across executions — remove one
  with `@unset name`. All others are asked on every execution; the dialog is prefilled with
  the values entered last time.
- **Cancel** aborts all exports of that execution ("Export cancelled" notification, no files;
  the rest of the script still runs);
  **Ignore** runs the queries as written, like DBeaver does.
- Classic form: the query below the `@exportnow` line also runs normally in the editor, so
  DBeaver asks a second time for that run (prefilled with the values just entered). The
  inline and `@@` forms only export, with a single dialog.
- Parameter recognition follows DBeaver's settings (*Preferences → Editors → SQL Editor → SQL
  Processing → Parameters*), e.g. whether anonymous `?` parameters are enabled.

### Delimiters, number formats and Excel

All options of DBeaver's CSV exporter can be set in `processor`, for example:

```sql
@exportnow {"type": "csv", "processor": {"delimiter": ";"}} SELECT * FROM t;
@exportnow {"type": "csv", "processor": {"delimiter": "\t"}} SELECT * FROM t;
@exportnow {"type": "csv", "processor": {"delimiter": "|", "quoteChar": "'", "rowDelimiter": "\\r\\n", "header": "none"}} SELECT * FROM t;
```

(`"\t"` is a tab in JSON. `rowDelimiter` takes DBeaver's values `default`, `\n`, `\r`, `\r\n`, so
in JSON the backslash is doubled: `"\\r\\n"`.)

**Excel preset.** `"preset": "excel"` writes a CSV that Excel opens correctly on a double click.
Like Excel itself, the column delimiter follows the decimal separator of the number format:

| Numbers written with | Column delimiter | Matches Excel with |
|---|---|---|
| decimal comma (`1.234,5`, e.g. German profile) | `;` | German/European regional settings |
| decimal point (`1,234.5`, e.g. English profile) | `,` | US/UK regional settings |

The preset also sets `formatNumbers: true` and UTF-8 with BOM (so umlauts display correctly).
Anything you set explicitly in `processor`/`consumer` wins over the preset — including
`processor.delimiter`.

**DBeaver data-format profiles.** With `formatNumbers` (on with the preset), numbers, dates and
times are written in the format of a DBeaver data-format profile — the profile decides the
decimal separator, and with the preset therefore also the CSV delimiter. Create the profile in
*Window → Preferences → Editors → Data Editor → Data Formats* (*Manage profiles…* → new profile,
pick locale e.g. *German (Germany)*, adjust patterns if needed, *Apply*; see DBeaver's
[Data format preferences](https://dbeaver.com/docs/dbeaver/Managing-Data-Formats/)
documentation), then select it by name:

```sql
@exportnow {"type": "csv", "preset": "excel", "formatterProfile": "German", "consumer": {"outputFolder": "/exports", "outputFilePattern": "orders_de"}}
SELECT * FROM orders;

@exportnow {"type": "csv", "preset": "excel", "formatterProfile": "English", "consumer": {"outputFolder": "/exports", "outputFilePattern": "orders_us"}}
SELECT * FROM orders;
```

The first file uses `;` and `1.234,5`, the second `,` and `1,234.5`.

- Without `formatterProfile` DBeaver's global data-format settings are used (by default the
  locale DBeaver runs with), and the preset's delimiter follows them.
- `formatterProfile` may also be given as `consumer.formatterProfile` (DBeaver's native key).
- An unknown profile name fails the export with the list of available profiles (DBeaver itself
  would silently fall back to the global profile).
- If you set a `processor.delimiter` that equals the decimal separator of formatted numbers,
  the export still runs but warns — in the log and in the "Export finished" notification —
  because Excel would split the numbers across columns.

## Installation

**Install New Software (recommended):**
1. Download the update-site zip (`org.jkiss.dbeaver.ext.exportnow.repository-*.zip`) from the
   [latest release](../../releases) or from a build's workflow artifacts.
2. In DBeaver: *Help → Install New Software… → Add… → Archive…*, select the zip.
3. Select *DBeaver Headless Export Command* and **untick *Contact all update sites during
   install to find required software***. The plugin has no dependencies beyond DBeaver itself,
   and with the box ticked DBeaver also queries its own update sites, so it asks you to trust
   each of their certificates first.
4. Finish the wizard. DBeaver asks once whether to trust *unsigned content* — the plugin and its
   feature, nothing else — confirm with *Trust Selected*, then restart DBeaver.

**Headless (p2 director):**

```sh
dbeaver -application org.eclipse.equinox.p2.director \
  -repository file:/path/to/extracted-update-site \
  -installIU org.jkiss.dbeaver.ext.exportnow.feature.feature.group \
  -nosplash
```

(Note: DBeaver does not reconcile a `dropins/` folder — use one of the installs above.)

## Performance & concurrency

The plugin is built for batch use: 50+ concurrent exports and 500,000+ rows × 100+ columns per
export are covered by integration tests against a real PostgreSQL (see below). What you should
know for production databases (PostgreSQL/Oracle/DB2):

- **Connections**: each export runs on its own connection (`producer.openNewConnections` — the
  plugin restores DBeaver's default `true`, which DBeaver's JSON parsing would otherwise silently
  drop for absent keys; set it to `false` explicitly only if you must share the session).
  Embedded drivers (SQLite/H2) always share the session.
- **Cap parallelism for shared servers**: add top-level `"maxJobCount": N` to the first
  `@exportnow` block — the run's exports are then executed at most N at a time (same semantics
  as DBeaver's data-transfer `maxJobCount`). Absent = unlimited. 4–16 is a good starting range;
  50 unbounded parallel queries can degrade a production server for other users.
- **No pre-count**: the plugin leaves `producer.queryRowCount` off, so no extra `COUNT(*)` runs
  before large exports.
- **Keep the fast defaults**: `splitOutFiles=false` (splitting flushes every row),
  `compressResults=false`, empty `lineFeedEscapeString`, `extractType=SINGLE_QUERY`.
- **Tuning knobs**: `producer.fetchSize` (default 10,000) and `consumer.valueFormat` exist, but
  the measurements below show DBeaver's defaults are already the fastest for wide text exports —
  change them only with evidence from your own workload.
- **Unique file names**: at high concurrency give each block its own
  `consumer.outputFilePattern`; identical names within the same minute otherwise rely on
  DBeaver's conflict renaming.
- **Heap**: DBeaver ships with `-Xmx1024m` in `dbeaver.ini`; raise it (e.g. `-Xmx4g`) for heavy
  parallel export sessions.

Measured on the reference setup (4-core Linux, PostgreSQL 16 in a local container,
500,000 rows × 110 columns = 561 MB CSV, exported from a materialized table):

| Settings | Duration | Throughput |
|---|---|---|
| defaults | **27.0 s** | **20.8 MB/s (~18,500 rows/s)** |
| `producer.fetchSize=50000` | 29.3 s | no gain — keep the default |
| `consumer.valueFormat=NATIVE` | 26.3–31.4 s | within run variance — keep the default |
| 55 concurrent exports (2,000 rows × 110 cols each) | 27 s total | all complete, per-export connections verified |

Note when benchmarking yourself: if the query *generates* its rows (e.g. `generate_series` with
per-row functions), you are measuring your database server, not the export — the identical
md5-generating query was 4.6× slower entirely due to server-side row computation.

## Building

Requires JDK 21 and Maven 3.9+:

```sh
mvn verify
```

- Plugin jar: `plugins/org.jkiss.dbeaver.ext.exportnow/target/`
- Update-site zip: `releng/repository/target/`

Code is formatted with [google-java-format](https://github.com/google/google-java-format)
(via Spotless); `mvn verify` fails on unformatted code — run `./mvnw spotless:apply` to fix.

The target platform resolves against the DBeaver release update site
(`https://dbeaver.io/update/latest/`, see `releng/target-platform/dbeaver.target`).

## CI

- **build.yml** — builds on every push/PR **and runs the Tycho integration tests**
  (`tests/org.jkiss.dbeaver.ext.exportnow.test`: JUnit 5 inside the OSGi runtime — extension
  registration, JSON/parameter parsing, and the DBeaver data-transfer registry ids we rely on),
  uploads the jar and update-site zip as workflow artifacts, and attaches both to the GitHub
  Release on `v*` tags.
  On pull requests it also uploads an installable test build (`exportnow-update-site-pr<N>`)
  and posts a PR comment with the download link and install steps — the downloaded zip can
  be selected directly in *Install New Software → Add… → Archive…*.
- **nightly.yml** — every night (and on demand) builds DBeaver **main** from source and compiles
  this plugin against it, catching upstream API breakage early.
