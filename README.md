# Sybase SQL Anywhere MCP Server

An open-source Model Context Protocol (MCP) server for SAP SQL Anywhere. The
server is JDBC-driver-agnostic - it loads whichever driver you point it at via
the `.prp` file - so it works equally well with:

- the **native SAP SQL Anywhere driver** (`sajdbc4.jar`, recommended on Windows
  when you already have SQL Anywhere installed), or
- the **pure-Java jConnect driver** (`jconn4.jar`, no native dependencies).

Originally based on [CData's MCP server framework](https://github.com/cdatasoftware/sap-sybase-mcp-server-by-cdata) (MIT license).

## What's new

- **2.1**: 7 more tools (`ping`, `list_schemas`, `describe_query`, `run_scalar`,
  `get_column_stats`, `get_value_distribution`, `get_table_permissions`),
  bind-parameter support in `run_query`/`execute`, full compatibility with SAP
  SQL Anywhere 17, and a Java test harness that exercises every tool against a
  live database. Validated end-to-end with **24/24 PASS**.
- **2.0**: 13 new tools, read-only mode by default, opt-in DML/DDL, schema
  allow/block lists, CSV/JSON/Markdown output, structured response metadata,
  masked password logs, prepared statements everywhere.

See [CHANGELOG.md](./CHANGELOG.md) for the full list.

## Features

- Connects to SQL Anywhere through any JDBC driver - swap drivers without
  recompiling.
- **23 tools** covering schema discovery, relationships, source code of
  procedures/views, sampling, counting, profiling, permissions, query plans,
  and ad-hoc SELECT (parameterized).
- **Read-only by default.** A separate `sybase_execute` tool is registered
  only when `AllowWrite=true`, and each call requires explicit `confirm=true`.
- Output in **CSV, JSON or Markdown**, with a metadata envelope (rowCount,
  truncated, elapsedMs, column types).
- **Allow/block schema lists** so the agent stays inside the data you want it
  to see.
- Server-side **row cap** and **query timeout** keep accidental
  `SELECT *` from melting your database or context window.
- **Prepared statements** for every metadata query (no string concat).
- Passwords are **masked in logs** and in JDBC error messages.

## Prerequisites

- Java 17+ (build and runtime)
- One of:
  - SAP SQL Anywhere 16+ installed (provides `Java\sajdbc4.jar` and the native
    `dbjdbc16.dll`). The installer adds `Bin64` to the system `PATH`; if you
    moved the installation, add it manually or pass
    `-Djava.library.path="<path-to-Bin64>"` in the `java` command, OR
  - The jConnect JAR (`jconn4.jar`) - works without any native libraries.

## Quick start

### 1. Build (or download)

Build locally:

```bash
git clone https://github.com/edurbs/sybase-sqlanywhere-mcp.git
cd sybase-sqlanywhere-mcp
mvn clean package -DskipTests
# produces target/sybase-mcp-server-jar-with-dependencies.jar
```

Or grab the pre-built JAR from the GitHub releases of the upstream project.

### 2. Create the `.prp`

Copy `sqlanywhere.prp.example` to `sqlanywhere.prp` and edit. Minimum example
using the native driver on Windows:

```properties
Prefix=sybase
DriverPath=C:\\Program Files\\SQL Anywhere 16\\Java\\sajdbc4.jar
DriverClass=sybase.jdbc4.sqlanywhere.IDriver
JdbcUrl=jdbc:sqlanywhere:links=tcpip(host=HOST);ServerName=srv;port=PORT;DatabaseName=mydb
User=myuser
Password=mypass
ReadOnly=true
```

The `.prp` file is gitignored. See [Configuration options](#configuration-options)
for the full list.

### 3. Register with your MCP client

Cursor / Claude Code (`~/.cursor/mcp.json` or `.mcp.json` in your project):

```json
{
  "mcpServers": {
    "sybase-sqlanywhere": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:\\path\\to\\sybase-mcp-server-jar-with-dependencies.jar",
        "C:\\path\\to\\sqlanywhere.prp"
      ]
    }
  }
}
```

Claude Desktop (`claude_desktop_config.json`): drop the `"type": "stdio"`,
otherwise identical.

## Available tools

All tool names are prefixed with the `Prefix` from your `.prp`
(default `sybase`). Replace `sybase` below with your prefix if different.

| Tool | Purpose |
|---|---|
| `sybase_ping` | `SELECT 1` health check |
| `sybase_database_info` | Product, version, charset, collation, current user, server runtime settings |
| `sybase_list_schemas` | Distinct schemas/owners with object count |
| `sybase_get_tables` | List tables / views with `pattern`, `type`, `limit`, `offset`, `format` |
| `sybase_get_columns` | Column definitions of a table (name, type, length, scale, default, nullable, remarks) |
| `sybase_get_table_info` | One-shot JSON: identity + columns + PK + FKs + indexes + estimated rows |
| `sybase_get_primary_keys` | PK columns of a table, in key order |
| `sybase_get_foreign_keys` | Outgoing and/or incoming FKs of a table |
| `sybase_get_indexes` | Indexes of a table with unique flag and column ordering |
| `sybase_get_table_permissions` | Table-level privileges granted to users/groups |
| `sybase_get_procedures` | List stored procedures and functions (with type) |
| `sybase_get_procedure_definition` | Source code (CREATE body) of a procedure/function |
| `sybase_get_view_definition` | SELECT body of a view |
| `sybase_search_objects` | LIKE search across tables/views/procedures/functions/columns |
| `sybase_sample_rows` | TOP N rows of a table, optionally random |
| `sybase_count_rows` | COUNT(*) with optional WHERE |
| `sybase_get_column_stats` | Profile: total rows, null count, distinct count, min/max |
| `sybase_get_value_distribution` | Top N most frequent values of a column with frequency and percent |
| `sybase_describe_query` | Describe a SELECT (columns, types) without executing it (via `sa_describe_query`) |
| `sybase_explain_plan` | SQL Anywhere execution plan (text or XML), with automatic engine fallback |
| `sybase_run_scalar` | Run a SELECT and return only the first column of the first row |
| `sybase_run_query` | Ad-hoc SELECT (read-only mode rejects DML/DDL). Optional `params` for `?` placeholders. |
| `sybase_execute` | INSERT/UPDATE/DELETE/DDL. Only registered when `AllowWrite=true`, requires `confirm=true` per call. Optional `params`. |

Every tabular tool accepts a `format` argument (`csv` / `json` / `markdown`) and
returns a `// meta: {...}` envelope as the first line with `rowCount`,
`elapsedMs`, `truncated`, column types and an optional `warning`.

### Recommended discovery flow for agents

1. `sybase_database_info` once to learn what server you're on, then
   `sybase_list_schemas` to know where to look.
2. `sybase_search_objects` or `sybase_get_tables` with `pattern=` to find the
   relevant objects.
3. `sybase_get_table_info` on a candidate table - gives columns, keys and FKs
   in a single document so the model can plan joins.
4. `sybase_sample_rows` for a few example rows, and `sybase_get_value_distribution`
   on key columns to understand cardinality and enumerated values.
5. `sybase_describe_query` to validate a query and discover its shape **without
   executing it**.
6. `sybase_run_query` (with `params` for parameterized queries) for the actual
   analytic query, with `format=json` if you want typed values, or
   `format=markdown` for human-friendly answers.
7. `sybase_explain_plan` if you want the model to suggest an index or rewrite.

## Testing

The repository ships with a Java test harness (`io.github.eduardo.sybasemcp.cli.TestAllTools`)
that exercises every tool against a live database, auto-discovers a real schema
/ table / column to use, and prints a PASS/FAIL/SKIP summary.

```bash
java -cp target/sybase-mcp-server-jar-with-dependencies.jar \
     io.github.eduardo.sybasemcp.cli.TestAllTools sqlanywhere.prp
```

Exits non-zero on any failure. Useful as a smoke test after upgrading the
driver, switching databases, or modifying tool code.

## Configuration options

All keys live in the `.prp` file (a standard Java properties file).

| Key | Required | Default | Purpose |
|---|:---:|---|---|
| `Prefix` | yes | - | Prefix for tool names (`<prefix>_get_tables`...) |
| `DriverPath` | yes | - | Filesystem path to the JDBC driver `.jar` |
| `DriverClass` | yes | - | Fully qualified class name of the JDBC driver |
| `JdbcUrl` | yes | - | JDBC URL of the database |
| `User` | no | - | Username (preferred over embedding in `JdbcUrl`) |
| `Password` | no | - | Password (masked in logs) |
| `ReadOnly` | no | `true` | When true, `run_query` only accepts SELECT/WITH/EXPLAIN/DESCRIBE |
| `AllowWrite` | no | `false` | When true, registers the `<prefix>_execute` tool for DML/DDL |
| `MaxRows` | no | `1000` | Server-wide cap on rows returned by any tool |
| `QueryTimeoutSeconds` | no | `30` | Server-wide query timeout |
| `DefaultFormat` | no | `csv` | Default output for tabular tools (`csv`, `json`, `markdown`) |
| `AllowSchemas` | no | (all) | Comma-separated allow-list (case-insensitive) |
| `BlockSchemas` | no | `SYS,DBO,RS_SYSTABGROUP,PUBLIC` | Comma-separated deny-list (case-insensitive) |
| `Tables` | no | - | Comma-separated tables to expose as MCP resources |
| `LogFile` | no | (stderr) | Path of the log file |

## Driver notes

### Native driver (`sajdbc4`) on Windows

The native driver loads `dbjdbc16.dll` from `<SQL Anywhere 16>\Bin64`. The SQL
Anywhere installer normally adds that folder to `PATH`, so it just works. If
you see `UnsatisfiedLinkError`, either:

- add `<SQL Anywhere 16>\Bin64` to `PATH`, or
- prepend `-Djava.library.path="C:\\Program Files\\SQL Anywhere 16\\Bin64"` to
  the `java` command in your `mcp.json`.

The native driver supports unqualified table names; you do **not** need the
TDS-only "always qualify with the owner" rule of jConnect.

### jConnect (`jconn4.jar`)

Pure Java, works anywhere. With jConnect you communicate via the TDS protocol
which requires:

- **Always** qualify table names with their owner: `SELECT * FROM owner.tbl`.
- Do **not** use double-quoted identifiers (`"tbl"`).

Use the `_get_tables` tool first to find the correct owner (the `Schema`
column).

## Security model

- **Read-only by default.** The default `ReadOnly=true` means the server only
  accepts SELECT-class statements through `run_query`. To run anything else,
  set `AllowWrite=true` in the `.prp` AND call `<prefix>_execute` with
  `confirm=true` per statement.
- **Allow/block schemas.** Out of the box, the agent cannot see `SYS`, `DBO`,
  `RS_SYSTABGROUP` or `PUBLIC`. Override with `BlockSchemas=` or restrict to
  specific schemas with `AllowSchemas=`.
- **No stacked statements.** `run_query` and `execute` reject payloads with
  more than one statement.
- **Caps.** Every tool obeys `MaxRows` and `QueryTimeoutSeconds`; per-call
  parameters may *lower* but never raise them.
- **Logs masked.** `Password`, `PWD` and `passwd` values are stripped from
  log lines and from JDBC exceptions before they're emitted.
- **Prepared statements.** All metadata queries use bound parameters. Tools
  that build SQL from identifiers (sample/count) require those identifiers to
  match `[A-Za-z_][A-Za-z0-9_$#]*` before they're interpolated.

## License

MIT - see [LICENSE](./LICENSE).
