package io.github.eduardo.sybasemcp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.eduardo.sybasemcp.Config;
import io.github.eduardo.sybasemcp.ITool;
import io.github.eduardo.sybasemcp.Program;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.PrintStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone test harness. Loads the same .prp the server uses, instantiates
 * every tool that would be registered, calls each one with realistic
 * arguments (auto-discovering a real schema/table/column from the database),
 * and prints a PASS/FAIL/SKIP summary.
 *
 * Bypasses the MCP stdio transport entirely so we can validate the tool
 * implementations against a live database.
 *
 * Usage:
 *   java -cp target/sybase-mcp-server-jar-with-dependencies.jar \
 *        io.github.eduardo.sybasemcp.cli.TestAllTools sqlanywhere.prp
 */
public class TestAllTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern META_LINE = Pattern.compile("^// meta:\\s*(\\{.*\\})\\s*$");

  static class Outcome {
    final String tool;
    final String status;
    final String detail;
    final long elapsedMs;

    Outcome(String tool, String status, String detail, long elapsedMs) {
      this.tool = tool;
      this.status = status;
      this.detail = detail;
      this.elapsedMs = elapsedMs;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: TestAllTools <path-to-.prp>");
      System.exit(2);
    }

    Config config = new Config();
    config.load(args[0]);
    PrintStream nullStream = new PrintStream(new java.io.OutputStream() {
      public void write(int b) {}
    });
    if (!config.validate(nullStream)) {
      System.err.println("Config validation failed - aborting tests.");
      System.exit(1);
    }

    List<ITool> tools = Program.buildToolList(config);

    Map<String, ITool> byName = indexByName(tools, config.getPrefix());
    System.out.println("Loaded " + tools.size() + " tools, prefix='" + config.getPrefix() + "'.");

    // Discover a real schema and table to drive metadata-aware tests
    String prefix = config.getPrefix();
    Discovered d = discoverContext(byName, prefix);
    System.out.println("Auto-discovered: schema=" + d.schema
        + " table=" + d.table + " column=" + d.column);
    System.out.println();

    List<Outcome> outcomes = new ArrayList<>();
    runAll(outcomes, byName, prefix, d, config.isWriteAllowed());
    printSummary(outcomes);
  }

  private static Map<String, ITool> indexByName(List<ITool> tools, String prefix) {
    Map<String, ITool> out = new LinkedHashMap<>();
    for (ITool t : tools) {
      String name = t.name();
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("Tool returned blank name: " + t.getClass().getSimpleName());
      }
      out.put(name, t);
    }
    return out;
  }

  static class Discovered {
    String schema;
    String table;
    String column;
  }

  private static Discovered discoverContext(Map<String, ITool> tools, String prefix) {
    Discovered d = new Discovered();
    // Try list_schemas first
    try {
      ITool listSchemas = tools.get(prefix + "_list_schemas");
      if (listSchemas != null) {
        McpSchema.CallToolResult r = listSchemas.run(Map.of("format", "json"));
        d.schema = firstJsonStringField(extractBody(r), "Schema");
      }
    } catch (Exception ignored) {}

    // Find a table
    try {
      ITool getTables = tools.get(prefix + "_get_tables");
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("limit", 1);
      args.put("type", "TABLE");
      args.put("format", "json");
      if (d.schema != null) args.put("schema", d.schema);
      McpSchema.CallToolResult r = getTables.run(args);
      String body = extractBody(r);
      if (d.schema == null) d.schema = firstJsonStringField(body, "Schema");
      d.table = firstJsonStringField(body, "Table");
    } catch (Exception ignored) {}

    // Find a column
    try {
      if (d.table != null) {
        ITool getColumns = tools.get(prefix + "_get_columns");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("schema", d.schema);
        args.put("table", d.table);
        args.put("format", "json");
        McpSchema.CallToolResult r = getColumns.run(args);
        d.column = firstJsonStringField(extractBody(r), "Column");
      }
    } catch (Exception ignored) {}
    return d;
  }

  private static void runAll(List<Outcome> outcomes, Map<String, ITool> tools,
                             String prefix, Discovered d, boolean writeAllowed) {
    // Tools that need no metadata
    test(outcomes, tools, prefix + "_ping", Map.of());
    test(outcomes, tools, prefix + "_database_info", Map.of());
    test(outcomes, tools, prefix + "_list_schemas", Map.of("format", "json"));
    test(outcomes, tools, prefix + "_get_tables", Map.of("limit", 5, "format", "json"));
    test(outcomes, tools, prefix + "_get_procedures", Map.of("limit", 5));
    test(outcomes, tools, prefix + "_search_objects",
        Map.of("query", "a", "limit", 3, "format", "json"));
    test(outcomes, tools, prefix + "_run_scalar", Map.of("sql", "SELECT 42"));
    test(outcomes, tools, prefix + "_run_query",
        Map.of("sql", "SELECT 1 AS one, 2 AS two", "format", "json"));
    test(outcomes, tools, prefix + "_run_query",
        Map.of("sql", "SELECT CAST(? AS VARCHAR(50)) AS bound, CAST(? AS INTEGER) AS bound2",
            "params", java.util.Arrays.asList("hello", 7),
            "format", "json"),
        "_run_query (bind params)");
    test(outcomes, tools, prefix + "_describe_query",
        Map.of("sql", "SELECT 1 AS a, CAST('x' AS VARCHAR(10)) AS b", "format", "json"));
    test(outcomes, tools, prefix + "_explain_plan",
        Map.of("sql", "SELECT 1", "textual", true));

    // Server safety: a write through run_query must fail when ReadOnly=true
    test(outcomes, tools, prefix + "_run_query",
        Map.of("sql", "DELETE FROM SYS.DUMMY"),
        "_run_query (write blocked in read-only mode)",
        /* expectError */ true);

    // Metadata-driven tools
    if (d.table == null) {
      skip(outcomes, prefix + "_get_columns", "no table discovered");
      skip(outcomes, prefix + "_get_table_info", "no table discovered");
      skip(outcomes, prefix + "_get_primary_keys", "no table discovered");
      skip(outcomes, prefix + "_get_foreign_keys", "no table discovered");
      skip(outcomes, prefix + "_get_indexes", "no table discovered");
      skip(outcomes, prefix + "_get_table_permissions", "no table discovered");
      skip(outcomes, prefix + "_sample_rows", "no table discovered");
      skip(outcomes, prefix + "_count_rows", "no table discovered");
    } else {
      Map<String, Object> tableArgs = new LinkedHashMap<>();
      tableArgs.put("schema", d.schema);
      tableArgs.put("table", d.table);
      tableArgs.put("format", "json");
      test(outcomes, tools, prefix + "_get_columns", tableArgs);
      test(outcomes, tools, prefix + "_get_table_info", tableArgs);
      test(outcomes, tools, prefix + "_get_primary_keys", tableArgs);
      test(outcomes, tools, prefix + "_get_foreign_keys", tableArgs);
      test(outcomes, tools, prefix + "_get_indexes", tableArgs);
      test(outcomes, tools, prefix + "_get_table_permissions", tableArgs);
      Map<String, Object> sampleArgs = new LinkedHashMap<>(tableArgs);
      sampleArgs.put("rows", 3);
      test(outcomes, tools, prefix + "_sample_rows", sampleArgs);
      Map<String, Object> countArgs = new LinkedHashMap<>();
      countArgs.put("schema", d.schema);
      countArgs.put("table", d.table);
      test(outcomes, tools, prefix + "_count_rows", countArgs);
    }

    if (d.table == null || d.column == null) {
      skip(outcomes, prefix + "_get_column_stats", "no column discovered");
      skip(outcomes, prefix + "_get_value_distribution", "no column discovered");
    } else {
      Map<String, Object> colArgs = new LinkedHashMap<>();
      colArgs.put("schema", d.schema);
      colArgs.put("table", d.table);
      colArgs.put("column", d.column);
      test(outcomes, tools, prefix + "_get_column_stats", colArgs);
      Map<String, Object> distArgs = new LinkedHashMap<>(colArgs);
      distArgs.put("topN", 5);
      distArgs.put("format", "json");
      test(outcomes, tools, prefix + "_get_value_distribution", distArgs);
    }

    // Optional: procedure/view detail. We don't know names, so we just check the
    // tool runs and returns a clear "not found" error - that still validates the
    // SQL.
    test(outcomes, tools, prefix + "_get_procedure_definition",
        Map.of("name", "__nonexistent_proc__"),
        "_get_procedure_definition (expected not-found)", true);
    test(outcomes, tools, prefix + "_get_view_definition",
        Map.of("name", "__nonexistent_view__"),
        "_get_view_definition (expected not-found)", true);

    // execute tool is only registered when AllowWrite=true
    if (writeAllowed && tools.containsKey(prefix + "_execute")) {
      test(outcomes, tools, prefix + "_execute",
          Map.of("sql", "UPDATE SYS.DUMMY SET dummy_col=1"),
          "_execute (dry-run)");
    }
  }

  private static void test(List<Outcome> outcomes, Map<String, ITool> tools,
                           String name, Map<String, Object> args) {
    test(outcomes, tools, name, args, null, false);
  }

  private static void test(List<Outcome> outcomes, Map<String, ITool> tools,
                           String name, Map<String, Object> args, String label) {
    test(outcomes, tools, name, args, label, false);
  }

  private static void test(List<Outcome> outcomes, Map<String, ITool> tools,
                           String name, Map<String, Object> args, String label, boolean expectError) {
    String displayName = label != null ? label : name;
    ITool tool = tools.get(name);
    if (tool == null) {
      outcomes.add(new Outcome(displayName, "SKIP", "tool not registered", 0));
      System.out.printf(" SKIP  %-50s (not registered)%n", displayName);
      return;
    }
    long t0 = System.currentTimeMillis();
    try {
      McpSchema.CallToolResult r = tool.run(args);
      long elapsed = System.currentTimeMillis() - t0;
      boolean isError = Boolean.TRUE.equals(r.isError());
      String body = extractBody(r);
      String snippet = snippet(body, 90);

      if (expectError && isError) {
        outcomes.add(new Outcome(displayName, "PASS", "expected error: " + snippet, elapsed));
        System.out.printf(" PASS  %-50s [%4d ms] (expected err) %s%n", displayName, elapsed, snippet);
      } else if (expectError && !isError) {
        outcomes.add(new Outcome(displayName, "FAIL", "expected error, got success", elapsed));
        System.out.printf(" FAIL  %-50s [%4d ms] expected error, got success: %s%n",
            displayName, elapsed, snippet);
      } else if (isError) {
        outcomes.add(new Outcome(displayName, "FAIL", snippet, elapsed));
        System.out.printf(" FAIL  %-50s [%4d ms] %s%n", displayName, elapsed, snippet);
      } else {
        outcomes.add(new Outcome(displayName, "PASS", snippet, elapsed));
        System.out.printf(" PASS  %-50s [%4d ms] %s%n", displayName, elapsed, snippet);
      }
    } catch (Throwable t) {
      long elapsed = System.currentTimeMillis() - t0;
      String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
      outcomes.add(new Outcome(displayName, "FAIL", msg, elapsed));
      System.out.printf(" FAIL  %-50s [%4d ms] %s%n", displayName, elapsed, msg);
    }
  }

  private static void skip(List<Outcome> outcomes, String name, String reason) {
    outcomes.add(new Outcome(name, "SKIP", reason, 0));
    System.out.printf(" SKIP  %-50s (%s)%n", name, reason);
  }

  private static String extractBody(McpSchema.CallToolResult r) {
    if (r == null || r.content() == null || r.content().isEmpty()) return "";
    Object c = r.content().get(0);
    if (c instanceof McpSchema.TextContent) {
      return ((McpSchema.TextContent) c).text();
    }
    return c.toString();
  }

  private static String snippet(String body, int max) {
    if (body == null) return "";
    String s = body.replace("\n", " ").trim();
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }

  /** Pulls the value of a string field from the first object of a JSON array body. */
  private static String firstJsonStringField(String body, String field) {
    if (body == null) return null;
    try {
      String stripped = body;
      Matcher m = META_LINE.matcher(body.split("\n", 2)[0]);
      if (m.matches()) {
        int nl = body.indexOf('\n');
        stripped = nl > 0 ? body.substring(nl + 1) : "";
      }
      stripped = stripped.trim();
      if (stripped.isEmpty()) return null;
      JsonNode root = MAPPER.readTree(stripped);
      if (root.isArray() && root.size() > 0) {
        JsonNode v = root.get(0).get(field);
        if (v != null && !v.isNull()) return v.asText();
      } else if (root.isObject()) {
        JsonNode v = root.get(field);
        if (v != null && !v.isNull()) return v.asText();
      }
    } catch (Exception ignored) {}
    return null;
  }

  private static void printSummary(List<Outcome> outcomes) {
    int pass = 0, fail = 0, skip = 0;
    for (Outcome o : outcomes) {
      switch (o.status) {
        case "PASS": pass++; break;
        case "FAIL": fail++; break;
        case "SKIP": skip++; break;
      }
    }
    System.out.println();
    System.out.println("============================================================");
    System.out.printf("Result: %d PASS, %d FAIL, %d SKIP (total %d tests)%n",
        pass, fail, skip, outcomes.size());
    System.out.println("============================================================");
    if (fail > 0) {
      System.out.println("Failed tests:");
      for (Outcome o : outcomes) {
        if (o.status.equals("FAIL")) {
          System.out.println("  - " + o.tool + " -> " + o.detail);
        }
      }
      System.exit(1);
    }
  }
}
