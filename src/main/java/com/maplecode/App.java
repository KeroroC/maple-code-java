package com.maplecode;

import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.ProviderRegistry;
import com.maplecode.ui.ReplLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        AppConfig config = ConfigLoader.load(configPath);
        LlmProvider provider = new ProviderRegistry().create(config);
        ReplLoop repl = ReplLoop.fromConfig(config, provider);
        repl.run();
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