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

```sql
@exportnow {"type": "csv", "consumer": {"outputFolder": "/data/export", "outputFilePattern": "customers"}};
SELECT * FROM customers;
@exportnow {"type": "csv", "consumer": {"outputFolder": "/data/export", "outputFilePattern": "orders"}};
SELECT * FROM orders;
SELECT 'script keeps going';
```

Execute the script (Alt+X). Both exports run in the background; the script does not stop.
Running exports appear as "Export data: …" jobs in the status-bar progress area and the
Progress view, where they can be **cancelled**; cancelling shows an "Export cancelled"
notification (a partially written output file may remain).

> **Statement-by-statement execution is supported.** Running only the `@exportnow` line
> (Ctrl+Enter) *arms* the export — you get an "Export armed" notification and an Output-tab
> message — and the **next query executed in that editor** (Ctrl+Enter, execute-in-new-tab,
> or a selection with Alt+X) is exported. The "-1 rows" grid is just DBeaver's standard
> display for control commands; the notifications carry the real feedback.

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
