package io.github.eduardo.sybasemcp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import io.github.eduardo.sybasemcp.resources.TableMetadataResource;
import io.github.eduardo.sybasemcp.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;

public class Program {

  private ServerMcpTransport transport;
  private Config config;
  private McpSyncServer mcpServer;
  private static final boolean STDIO = true;

  public void init(String configPath) throws Exception {
    this.config = new Config();
    this.config.load(configPath);
    if (!StringUtil.isNullOrEmpty(this.config.getLogFile())) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
      System.setProperty("org.slf4j.simpleLogger.logFile", this.config.getLogFile());
      System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
      System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
      System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
    }
    if (!this.config.validate(System.err)) {
      System.exit(-1);
    }

    this.transport = new StdioServerTransport(new ObjectMapper());
  }

  public void configureMcp() throws Exception {
    McpServer.SyncSpec spec =
        McpServer.sync(this.transport)
            .serverInfo(this.config.getServerName(), this.config.getServerVersion())
            .capabilities(
                McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(false, true)
                    .build()
            );

    registerResources(this.config, spec);
    registerTools(this.config, spec);

    this.mcpServer = spec.build();
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: <properties-file-path>");
      System.exit(-1);
    }

    final Program p = new Program();
    p.init(args[0]);
    p.configureMcp();
    if (!STDIO) {
      // p.runHttpServer();
    } else {
      Runtime.getRuntime().addShutdownHook(new Thread() {
        public void run() {
          synchronized (p) {
            p.notify();
          }
        }
      });
      synchronized (p) {
        p.wait();
        p.mcpServer.closeGracefully();
      }
    }
  }

  private static void registerResources(Config config, McpServer.SyncSpec mcp) throws SQLException {
    List<Table> tables = config.getTables();
    if (tables.isEmpty()) return;
    TableMetadataResource resource = new TableMetadataResource(config);

    for (Table r : tables) {
      resource.register(mcp, r);
    }
  }

  private static void registerTools(Config config, McpServer.SyncSpec mcp) throws Exception {
    List<ITool> tools = buildToolList(config);
    for (ITool tool : tools) {
      if (tool != null) {
        tool.register(mcp);
      }
    }
  }

  public static List<ITool> buildToolList(Config config) {
    List<ITool> tools = new ArrayList<>();
    tools.add(new PingTool(config));
    tools.add(new DatabaseInfoTool(config));
    tools.add(new ListSchemasTool(config));
    tools.add(new GetTablesTool(config));
    tools.add(new GetColumnsTool(config));
    tools.add(new GetTableInfoTool(config));
    tools.add(new GetPrimaryKeysTool(config));
    tools.add(new GetForeignKeysTool(config));
    tools.add(new GetIndexesTool(config));
    tools.add(new GetTablePermissionsTool(config));
    tools.add(new GetProceduresTool(config));
    tools.add(new GetProcedureDefinitionTool(config));
    tools.add(new GetViewDefinitionTool(config));
    tools.add(new SearchObjectsTool(config));
    tools.add(new SampleRowsTool(config));
    tools.add(new CountRowsTool(config));
    tools.add(new GetColumnStatsTool(config));
    tools.add(new GetValueDistributionTool(config));
    tools.add(new DescribeQueryTool(config));
    tools.add(new ExplainPlanTool(config));
    tools.add(new RunScalarTool(config));
    tools.add(new RunQueryTool(config));

    if (config.isWriteAllowed()) {
      tools.add(new ExecuteTool(config));
    }
    return tools;
  }
}
