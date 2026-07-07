package com.maplecode;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.compression.*;
import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.mcp.adapter.McpToolAdapter;
import com.maplecode.mcp.client.McpClient;
import com.maplecode.mcp.client.McpClientBootstrap;
import com.maplecode.mcp.config.McpServerConfigLoader;
import com.maplecode.mcp.config.McpServerSpec;
import com.maplecode.permission.BlacklistCheck;
import com.maplecode.permission.HitlCheck;
import com.maplecode.permission.JLineInputSource;
import com.maplecode.permission.ModeCheck;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionFileLoader;
import com.maplecode.permission.PrintStreamOutputSink;
import com.maplecode.permission.RuleCheck;
import com.maplecode.permission.RuleSet;
import com.maplecode.permission.SandboxCheck;
import com.maplecode.prompt.DefaultSections;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.PromptAssembler;
import com.maplecode.prompt.SectionContext;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.ProviderRegistry;
import com.maplecode.tool.EditFileTool;
import com.maplecode.tool.ExecTool;
import com.maplecode.tool.GlobTool;
import com.maplecode.tool.GrepTool;
import com.maplecode.tool.ReadFileTool;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.WriteFileTool;
import com.maplecode.ui.ReplLoop;
import com.maplecode.ui.StreamPrinter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class App {

    public static void main(String[] args) throws Exception {
        Path configPath = locateConfig(args);
        if (configPath == null) {
            System.err.println("no config found. Looked in:");
            System.err.println("  --config <path> argument");
            System.err.println("  ./maplecode.yaml");
            System.err.println("  ~/.maplecode/config.yaml");
            System.err.println("Run with `maplecode --config path/to/config.yaml`");
            System.exit(78);
        }
        AppConfig raw = ConfigLoader.load(configPath);
        Path cwd = Paths.get(System.getProperty("user.dir"));

        // === MCP server 装配 ===
        Map<String, McpClient> clients;
        AppConfig.McpConfig mcpCfg = raw.mcpConfig();
        if (mcpCfg == null || !mcpCfg.enabled()) {
            if (mcpCfg != null) {
                System.err.println("[mcp] disabled by config (mcp_servers.enabled=false)");
            }
            clients = Map.of();
        } else {
            Path userMcpFile = Paths.get(System.getProperty("user.home"),
                                          ".maplecode", "mcp_servers.yaml");
            List<McpServerSpec> specs = new McpServerConfigLoader().loadAll(cwd, userMcpFile);
            specs = specs.stream().filter(McpServerSpec::enabled).toList();
            if (specs.isEmpty()) {
                clients = Map.of();
            } else {
                clients = new McpClientBootstrap(mcpCfg.startupTimeoutDuration()).start(specs);
                if (!clients.isEmpty()) {
                    int total = clients.values().stream()
                        .mapToInt(c -> { try { return c.cachedTools().size(); } catch (Exception e) { return 0; } })
                        .sum();
                    String names = clients.keySet().stream()
                        .map(n -> n + " (" + clients.get(n).cachedTools().size() + " tools)")
                        .reduce((a, b) -> a + ", " + b).orElse("");
                    System.err.println("[mcp] connected: " + names + " — total " + total + " tools");
                }
            }
        }

        LlmProvider provider = new ProviderRegistry().create(raw);

        List<Tool> builtins = List.of(
            new ReadFileTool(), new WriteFileTool(), new EditFileTool(),
            new ExecTool(),    new GlobTool(),     new GrepTool());
        List<Tool> mcpTools = clients.values().stream()
            .flatMap(c -> {
                try {
                    return c.cachedTools().stream()
                        .map(t -> McpToolAdapter.of(c, t));
                } catch (Exception e) {
                    System.err.println("[mcp:" + c.name() + "] WARN: failed to load tools: " + e.getMessage());
                    e.printStackTrace(System.err);
                    return Stream.empty();
                }
            }).toList();
        List<Tool> allTools = new ArrayList<>(builtins);
        allTools.addAll(mcpTools);
        ToolRegistry registry = new ToolRegistry(allTools);

        // Permission engine
        Path userPermFile = Paths.get(System.getProperty("user.home"), ".maplecode", "permissions.yaml");
        RuleSet ruleSet = PermissionFileLoader.loadAll(cwd, userPermFile);

        var reader = buildLineReader();
        HitlCheck hitlCheck = new HitlCheck(
            new JLineInputSource(reader),
            new PrintStreamOutputSink(System.out));
        PermissionEngine engine = new PermissionEngine(
            List.of(
                new BlacklistCheck(),
                new SandboxCheck(cwd),
                new RuleCheck(ruleSet),
                new ModeCheck(),
                hitlCheck),
            raw.permissionMode());
        hitlCheck.setEngine(engine);

        // 压缩系统（v6）
        CompressionConfig compressionCfg = CompressionConfig.fromAppConfig(raw.contextWindow());
        CompressionStorage compressionStorage = new CompressionStorage(
            Paths.get(System.getProperty("user.home"), ".maplecode", "cache",
                      "session-" + UUID.randomUUID()));
        FailureCounter failureCounter = new FailureCounter(compressionCfg.failureThreshold());
        CompressionContext compressionCtx = new CompressionContext(compressionCfg, compressionStorage, failureCounter);
        Offloader offloader = new Offloader(compressionStorage);
        ConversationSummarizer summarizer = new ConversationSummarizer(
            provider, raw.model(), raw.summarizerModel());
        CompressionCoordinator coord = new CompressionCoordinator(
            compressionCtx, provider, offloader, summarizer);

        ToolExecutor executor = new ToolExecutor(registry, engine);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (var c : clients.values()) {
                try { c.close(); } catch (Exception e) {
                    System.err.println("[mcp:" + c.name() + "] WARN: shutdown close failed: " + e.getMessage());
                }
            }
        }, "mcp-shutdown"));
        Runtime.getRuntime().addShutdownHook(new Thread(coord::close, "compression-shutdown"));

        // 启动期组装 systemBlocks
        DynamicContext env = DynamicContext.capture(cwd);
        var tools = registry.all();
        var sections = DefaultSections.standard(env, tools, PlanMode.NORMAL, raw.yamlPrompt());
        var sectionCtx = new SectionContext(tools, env, PlanMode.NORMAL);
        var blocks = new PromptAssembler().assemble(sections, sectionCtx);

        AgentConfig agentConfig = AgentConfig.fromAppConfig(raw)
            .withSystemBlocks(blocks);

        ReplLoop repl = new ReplLoop(raw, provider, new StreamPrinter(System.out),
            reader, registry, executor, engine, agentConfig, coord);
        repl.run();
    }

    private static org.jline.reader.LineReader buildLineReader() throws java.io.IOException {
        org.jline.terminal.Terminal terminal =
            org.jline.terminal.TerminalBuilder.builder().system(true).build();
        return org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
    }

    private static Path locateConfig(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--config")) return Paths.get(args[i + 1]);
        }
        Path local = Paths.get("maplecode.yaml");
        if (Files.exists(local)) return local;
        Path home = Paths.get(System.getProperty("user.home"), ".maplecode", "config.yaml");
        if (Files.exists(home)) return home;
        return null;
    }
}
