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

## Installation

**Install New Software (recommended):**
1. Download the update-site zip (`org.jkiss.dbeaver.ext.exportnow.repository-*.zip`) from the
   [latest release](../../releases) or from a build's workflow artifacts.
2. In DBeaver: *Help → Install New Software… → Add… → Archive…*, select the zip.
3. Select *DBeaver Headless Export Command*, finish the wizard, restart DBeaver.

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

The target platform resolves against the DBeaver release update site
(`https://dbeaver.io/update/latest/`, see `releng/target-platform/dbeaver.target`).

## CI

- **build.yml** — builds on every push/PR **and runs the Tycho integration tests**
  (`tests/org.jkiss.dbeaver.ext.exportnow.test`: JUnit 5 inside the OSGi runtime — extension
  registration, JSON/parameter parsing, and the DBeaver data-transfer registry ids we rely on),
  uploads the jar and update-site zip as workflow artifacts, and attaches both to the GitHub
  Release on `v*` tags.
- **nightly.yml** — every night (and on demand) builds DBeaver **main** from source and compiles
  this plugin against it, catching upstream API breakage early.
