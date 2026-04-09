# Sybase SQL Anywhere MCP Server

A free, open-source Model Context Protocol (MCP) server for SAP Sybase SQL Anywhere, powered by the jConnect JDBC driver.

Based on [CData's MCP server framework](https://github.com/cdatasoftware/sap-sybase-mcp-server-by-cdata) (MIT license), modified to work with jConnect instead of the commercial CData driver.

## Features

- Connects to SQL Anywhere databases via jConnect (free, pure Java, no native libraries)
- Exposes three MCP tools: `get_tables`, `get_columns`, `run_query`
- Works with Claude Code, Claude Desktop, and any MCP-compatible client
- Uses SQL Anywhere system views for metadata (SYS.SYSTAB, SYS.SYSCOLUMN, SYS.SYSDOMAIN)

## Prerequisites

- **Java 17+** (for building and running)
- **jConnect JDBC driver** (`jconn4.jar`) — included with SQL Anywhere installations, or available from DBeaver's driver cache

## Building

```bash
git clone https://github.com/eduardo/sybase-sqlanywhere-mcp.git
cd sybase-sqlanywhere-mcp
mvn clean package
```

This produces `target/sybase-mcp-server-jar-with-dependencies.jar`.

## Configuration

Create a `.prp` file (see `sqlanywhere.prp.example` for a template):

```properties
Prefix=sybase
DriverPath=/path/to/jconn4.jar
DriverClass=com.sybase.jdbc4.jdbc.SybDriver
JdbcUrl=jdbc:sybase:Tds=HOST:PORT/DATABASE?user=USERNAME&password=PASSWORD
Tables=
```

**Note**: `.prp` files contain credentials and are excluded from git via `.gitignore`.

### Getting jConnect

- From a SQL Anywhere installation: `Bin64/jconn4.jar`
- From DBeaver's driver cache: `~/.local/share/DBeaverData/drivers/drivers/sybase/jconnect/jconn4.jar`

## Usage with Claude Code

Add to your project's `.mcp.json`:

```json
{
  "mcpServers": {
    "sybase": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "/path/to/sybase-mcp-server-jar-with-dependencies.jar",
        "/path/to/sqlanywhere.prp"
      ]
    }
  }
}
```

## Usage with Claude Desktop

Add to your Claude Desktop config (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "sybase": {
      "command": "/path/to/java",
      "args": [
        "-jar",
        "/path/to/sybase-mcp-server-jar-with-dependencies.jar",
        "/path/to/sqlanywhere.prp"
      ]
    }
  }
}
```

## Important: Table Name Qualification

jConnect communicates with SQL Anywhere via the TDS protocol. TDS requires **table names to be qualified with their owner/schema**:

```sql
-- This works:
SELECT * FROM owner.tablename

-- This does NOT work (TDS cannot resolve unqualified names):
SELECT * FROM tablename
```

Use the `get_tables` tool first to find the correct owner (Schema column), then qualify names in your queries.

**Also**: Do NOT use double-quoted identifiers (`"tablename"`) — they cause syntax errors through TDS.

## Available Tools

| Tool | Description |
|------|-------------|
| `sybase_get_tables` | List all tables (returns Schema, Table, Type, Remarks) |
| `sybase_get_columns` | List columns for a table (returns Schema, Table, Column, DataType, Nullable) |
| `sybase_run_query` | Execute a SQL SELECT query |

## License

MIT License — see [LICENSE](./LICENSE) for details.
