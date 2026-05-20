# Changelog

## 2.1.0

### Added - new tools (7)

- `sybase_ping` - lightweight `SELECT 1` health check, returns `{ok, elapsedMs}`.
- `sybase_list_schemas` - distinct schemas with the count of tables/views each owns.
- `sybase_describe_query` - **describes** a SELECT (columns, types, nullability,
  base table/column) using `sa_describe_query` **without executing it**.
- `sybase_run_scalar` - runs a SELECT and returns the first column of the first
  row as a single typed value. Convenient for `COUNT(*)`, `MAX(date)`, etc.
- `sybase_get_column_stats` - profiles a column: total rows, null count, distinct
  count, null ratio, cardinality ratio, optional min/max.
- `sybase_get_value_distribution` - top N most frequent values of a column with
  count and percent. Essential for the agent to grasp cardinality.
- `sybase_get_table_permissions` - per-grantee privileges (select/insert/update
  /delete/references/alter) from `SYS.SYSTABLEPERM`.

### Added - bind parameters

- `sybase_run_query` and `sybase_execute` now accept an optional `params` array.
  When present, the statement is run as a `PreparedStatement` and the values are
  bound in order to the `?` placeholders. Numbers, booleans, strings, bytes and
  nulls are handled.

### Improved - SAP SQL Anywhere 17 compatibility

After testing against SA 17.0.11.7312 with the native `sajdbc4` driver:

- `get_procedures`, `get_procedure_definition`, `search_objects`: do not depend
  on `SYS.SYSPROCEDURE.proc_type` (does not exist in SA 17). Routine type is
  inferred from `SYS.SYSPROCPARM.parm_type=4` (RETURNS - means FUNCTION).
- `get_foreign_keys`, `get_table_info`: do not depend on `SYS.SYSFKEY.role`
  (does not exist in SA 17). FK name now comes from `SYS.SYSIDX.index_name`.
- `get_view_definition`: tries `SYS.SYSTAB.view_def` first, then falls back to
  `INFORMATION_SCHEMA.VIEWS`.
- `explain_plan`: tries `dbo.sa_get_plan` (with detail), then `EXPLANATION()`,
  then `PLAN()` until one succeeds. The chosen engine is reported in the output
  (`-- via: ...`).
- `get_tables`: row collection no longer uses `List.of` (which forbids nulls).

### Added - test harness

- `io.github.eduardo.sybasemcp.cli.TestAllTools` is a standalone main class
  that loads the `.prp`, instantiates every tool, auto-discovers a real
  schema/table/column from the live database and runs each tool with realistic
  arguments. Reports PASS/FAIL/SKIP and exits non-zero on any failure.
- Run with:
  ```
  java -cp target/sybase-mcp-server-jar-with-dependencies.jar \
       io.github.eduardo.sybasemcp.cli.TestAllTools sqlanywhere.prp
  ```

### Added - interface change

- `ITool` now declares a `String name()` method returning the registered tool
  name. Custom tool implementations need to add this one-line method.

## 2.0.0

Major release. Renames are intentional (some tools changed signatures), but
existing tools still work with their old defaults if you only pass the original
arguments.

### Added - new tools

- `sybase_database_info` - product/version/charset/collation/page size of the
  database, plus the server runtime settings (read-only mode, max rows, etc.).
- `sybase_get_table_info` - one-shot description of a table: identity, columns,
  primary key, outgoing and incoming foreign keys, indexes, estimated row
  count, all in a single JSON document. Reduces round-trips for the agent.
- `sybase_get_primary_keys` - primary key columns of a table, in order.
- `sybase_get_foreign_keys` - outgoing and incoming foreign keys for a table.
  Critical for the agent to discover JOIN paths.
- `sybase_get_indexes` - indexes of a table, with unique flag and ordinal columns.
- `sybase_get_procedures` - lists stored procedures and functions.
- `sybase_get_procedure_definition` - returns the source code of a procedure or
  function (the CREATE body).
- `sybase_get_view_definition` - returns the SELECT body of a view.
- `sybase_search_objects` - case-insensitive search across tables, views,
  procedures, functions and columns by name.
- `sybase_sample_rows` - safe TOP N from a table (optional random ordering).
- `sybase_count_rows` - COUNT(*) shortcut with optional WHERE.
- `sybase_explain_plan` - SQL Anywhere execution plan (text or XML) for a SELECT.
- `sybase_execute` - executes INSERT/UPDATE/DELETE/DDL. **Disabled by default.**
  Only registered when `AllowWrite=true` in the `.prp` file, and every call
  requires `confirm=true` to actually run.

### Changed - improvements to existing tools

- `sybase_run_query`
  - Enforces a row cap (`MaxRows`, default 1000) and a query timeout
    (`QueryTimeoutSeconds`, default 30s). Both can be lowered per call via
    `maxRows`/`timeoutSeconds`, but the server cap is always the upper bound.
  - Refuses DML/DDL when the server is in read-only mode (the default).
  - Rejects multi-statement payloads (no stacked queries through a stray `;`).
  - Returns a response envelope with `rowCount`, `truncated`, `elapsedMs`,
    column metadata and an optional `warning` message (e.g. when truncated).
  - Dates/times/timestamps are now serialized as ISO 8601, decoupled from the
    locale of the calling environment.
  - Binary columns are now hex-previewed instead of being dumped as garbage.
- `sybase_get_tables`
  - Adds `pattern` (SQL LIKE), `type` (TABLE/VIEW/ALL), `limit`, `offset` and
    `format` arguments.
  - Uses `PreparedStatement` for all filters (defense in depth).
  - Includes table remarks (`SYS.SYSREMARK`).
  - Honours the `AllowSchemas` / `BlockSchemas` policy.
- `sybase_get_columns`
  - Returns more fields: position, length, scale, default, remarks.
  - `format` argument, `PreparedStatement`, schema filter, same as above.

### Added - cross-cutting

- `format` argument on every tabular tool. Accepts `csv` (default), `json` and
  `markdown`. Default can be set via `DefaultFormat` in the `.prp` file.
- All responses now include a `// meta: {...}` JSON envelope on the first line
  with `rowCount`, `elapsedMs`, `truncated`, `format` and column types.
- Passwords are masked in logs and in JDBC error messages.
- `AllowSchemas` / `BlockSchemas` configurable allow- and deny-lists. By default
  `SYS,DBO,RS_SYSTABGROUP,PUBLIC` are blocked so the agent doesn't trip over
  system catalogs.
- `ReadOnly`, `MaxRows`, `QueryTimeoutSeconds`, `User`, `Password`,
  `DefaultFormat`, `AllowWrite` options in the `.prp` file.

### Fixed

- `CsvWriter` was emitting the same output for `null` and for the empty string.
  Now empty strings are quoted (`""`) and nulls render as nothing - LLMs no
  longer have to guess.
- `Config.quoteIdentifier` did not escape the quote character itself when the
  identifier contained one.
- `Program.registerResources` was registering `TableMetadataResource` even when
  `Tables=` was empty. It now no-ops in that case.

### Compatibility notes

- The minimum required Java version is still **17**.
- The MCP SDK version is still 0.8.1 (a future release will move to 0.10.x).
- The driver-loading mechanism is unchanged - both the SAP native driver
  (`sajdbc4.jar`) and the pure-Java jConnect driver continue to work, no Java
  code changes needed when switching.

## 1.0.0

Initial fork of CData's MCP server framework, adapted to load any JDBC driver
through the `.prp` file. Exposed `get_tables`, `get_columns` and `run_query`
against SQL Anywhere via jConnect.
