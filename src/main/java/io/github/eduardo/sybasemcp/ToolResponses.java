package io.github.eduardo.sybasemcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;

/** Builds CallToolResult objects in the format the MCP 0.8.1 SDK expects. */
public final class ToolResponses {
  private ToolResponses() {}

  public static McpSchema.CallToolResult text(String body) {
    return build(body, false);
  }

  public static McpSchema.CallToolResult error(String message) {
    return build("ERROR: " + message, true);
  }

  private static McpSchema.CallToolResult build(String body, boolean isError) {
    List<McpSchema.Role> roles = new ArrayList<>();
    roles.add(McpSchema.Role.USER);
    List<McpSchema.Content> content = new ArrayList<>();
    content.add(new McpSchema.TextContent(roles, 1.0, body));
    return new McpSchema.CallToolResult(content, isError);
  }

  /** Convenience to extract an optional string argument from a tool call. */
  public static String optString(java.util.Map<String, Object> args, String name) {
    Object v = args.get(name);
    if (v == null) return null;
    String s = v.toString().trim();
    return s.isEmpty() ? null : s;
  }

  public static Integer optInteger(java.util.Map<String, Object> args, String name) {
    Object v = args.get(name);
    if (v == null) return null;
    if (v instanceof Number) return ((Number) v).intValue();
    try {
      String s = v.toString().trim();
      return s.isEmpty() ? null : Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public static int optIntegerOr(java.util.Map<String, Object> args, String name, int fallback) {
    Integer v = optInteger(args, name);
    return v == null ? fallback : v;
  }

  public static Boolean optBoolean(java.util.Map<String, Object> args, String name) {
    Object v = args.get(name);
    if (v == null) return null;
    if (v instanceof Boolean) return (Boolean) v;
    String s = v.toString().trim().toLowerCase();
    if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
    if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
    return null;
  }
}
