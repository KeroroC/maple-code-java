package com.maplecode;

import com.maplecode.agent.AgentConfig;
import com.maplecode.agent.PlanMode;
import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.prompt.DefaultSections;
import com.maplecode.prompt.DynamicContext;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.prompt.PromptAssembler;
import com.maplecode.prompt.SectionContext;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.ProviderRegistry;
import com.maplecode.tool.EditFileTool;
import com.maplecode.tool.ExecTool;
import com.maplecode.tool.GlobTool;
import com.maplecode.tool.GrepTool;
import com.maplecode.tool.ReadFileTool;
import com.maplecode.tool.ToolRegistry;
import com.maplecode.tool.WriteFileTool;
import com.maplecode.ui.ReplLoop;
import com.maplecode.ui.StreamPrinter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
        LlmProvider provider = new ProviderRegistry().create(raw);
        ToolRegistry registry = new ToolRegistry(List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ExecTool(),
            new GlobTool(),
            new GrepTool()
        ));

        // 启动期组装 systemBlocks
        Path cwd = Paths.get(System.getProperty("user.dir"));
        DynamicContext env = DynamicContext.capture(cwd);
        var tools = registry.all();
        var sections = DefaultSections.standard(env, tools, PlanMode.NORMAL, raw.yamlPrompt());
        var sectionCtx = new SectionContext(tools, env, PlanMode.NORMAL);
        var blocks = new PromptAssembler().assemble(sections, sectionCtx);

        AgentConfig agentConfig = new AgentConfig(
            raw.model(), blocks, raw.thinking(),
            25, 3, PlanMode.NORMAL, PlanModeReminder.State.initial());

        ReplLoop repl = new ReplLoop(raw, provider, new StreamPrinter(System.out),
            buildLineReader(), registry, agentConfig);
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
