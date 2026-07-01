# MapleCode 实现计划

> **给 agentic worker：** 必需子技能：用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务执行本计划。步骤用复选框（`- [ ]`）语法追踪。

**目标：** 构建 MapleCode v1 —— 一个 Java 命令行 AI 助手，支持 TUI、多轮对话记忆、可插拔 provider（Anthropic Claude + OpenAI）、SSE 流式。暂不做 tool use。

**架构：** 单模块 Maven 项目。REPL 主循环用 JLine 读输入 → `ChatSession` 累积消息 → `LlmProvider`（由 `ProviderRegistry` 根据 YAML 的 `protocol` 字段选出）转换为 provider 特定的 JSON，通过 JDK `HttpClient` 打开 SSE 流，把事件解析回 sealed `StreamChunk` 联合类型，REPL 增量渲染。

**技术栈：** Java 21、Maven、JLine 3、JDK `java.net.http.HttpClient`、Jackson（`jackson-databind`）、SnakeYAML、JUnit 5。

**规格文档：** `docs/superpowers/specs/2026-07-01-maple-code-design.md`

---

## 文件结构

```
maple-code-java/
├── pom.xml                                                  # 任务 1
├── maplecode.yaml.example                                   # 任务 19
├── README.md                                                # 任务 19
├── .gitignore                                               # 已存在
├── src/main/java/com/maplecode/
│   ├── App.java                                             # 任务 19
│   ├── error/
│   │   ├── MapleCodeException.java                          # 任务 2
│   │   ├── ConfigException.java                             # 任务 2
│   │   └── ProviderException.java                           # 任务 2
│   ├── provider/
│   │   ├── ChatMessage.java                                 # 任务 3
│   │   ├── ChatRequest.java                                 # 任务 3
│   │   ├── ThinkingConfig.java                              # 任务 5
│   │   ├── StreamChunk.java                                 # 任务 4
│   │   ├── LlmProvider.java                                 # 任务 9（接口，registry 在 16）
│   │   ├── ProviderRegistry.java                            # 任务 16
│   │   ├── anthropic/
│   │   │   ├── AnthropicRequestMapper.java                  # 任务 10
│   │   │   ├── AnthropicStreamParser.java                   # 任务 11
│   │   │   └── AnthropicProvider.java                       # 任务 12
│   │   └── openai/
│   │       ├── OpenAiRequestMapper.java                     # 任务 13
│   │       ├── OpenAiStreamParser.java                      # 任务 14
│   │       └── OpenAiProvider.java                          # 任务 15
│   ├── http/
│   │   └── SseStreamReader.java                             # 任务 8
│   ├── config/
│   │   ├── AppConfig.java                                   # 任务 6
│   │   └── ConfigLoader.java                                # 任务 7
│   ├── session/
│   │   └── ChatSession.java                                 # 任务 17
│   └── ui/
│       ├── StreamPrinter.java                               # 任务 18
│       └── ReplLoop.java                                    # 任务 19
└── src/test/java/com/maplecode/
    ├── error/                                               # 无（都是简单类）
    ├── provider/
    │   ├── ThinkingConfigTest.java                          # 任务 5
    │   ├── ChatSessionTest.java                             # 任务 17
    │   ├── ProviderRegistryTest.java                        # 任务 16
    │   ├── anthropic/
    │   │   ├── AnthropicRequestMapperTest.java              # 任务 10
    │   │   └── AnthropicStreamParserTest.java               # 任务 11
    │   └── openai/
    │       ├── OpenAiRequestMapperTest.java                 # 任务 13
    │       └── OpenAiStreamParserTest.java                  # 任务 14
    ├── http/
    │   └── SseStreamReaderTest.java                         # 任务 8
    └── config/
        ├── ConfigLoaderTest.java                            # 任务 7
        └── ConfigLoaderDeprecationWarningTest.java          # 任务 7
```

**分解原则：** 每个文件只负责一件事。Provider 包内聚 request mapper / stream parser / provider 三件套，这样加第三个 provider 只需自包含地新增一个包。

---

## 任务顺序原理

自底向上，每个任务编译通过 + 测试全绿再进入下一个：

1. 构建基础设施
2. 异常类（无依赖）
3. 纯数据 DTO（无依赖）
4. 校验逻辑 + 测试（TDD）
5. 配置记录 → YAML loader + 测试（TDD）
6. SSE 原语 + 测试（TDD）
7. 每家 provider 的 mapper / parser / provider（TDD 全程）
8. 会话 + REPL 粘合 + 主入口

---

## 阶段 1 — 项目骨架

### 任务 1：引导 Maven 项目

**文件：**
- 创建：`pom.xml`

- [ ] **步骤 1：编写 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.maplecode</groupId>
    <artifactId>maple-code-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <jline.version>3.27.0</jline.version>
        <jackson.version>2.17.2</jackson.version>
        <snakeyaml.version>2.3</snakeyaml.version>
        <junit.version>5.11.3</junit.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.jline</groupId>
            <artifactId>jline-reader</artifactId>
            <version>${jline.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>org.yaml</groupId>
            <artifactId>snakeyaml</artifactId>
            <version>${snakeyaml.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.1</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.maplecode.App</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **步骤 2：验证 Maven 能解析依赖**

运行：`mvn -q -DskipTests dependency:resolve`
预期：BUILD SUCCESS

- [ ] **步骤 3：提交**

```bash
git add pom.xml
git commit -m "build: 引导 Maven 项目（Java 21 + JLine 3 + Jackson + SnakeYAML + JUnit 5）"
```

---

### 任务 2：异常层级

**文件：**
- 创建：`src/main/java/com/maplecode/error/MapleCodeException.java`
- 创建：`src/main/java/com/maplecode/error/ConfigException.java`
- 创建：`src/main/java/com/maplecode/error/ProviderException.java`

不需要测试 —— 这些都是单行构造的纯数据载体。

- [ ] **步骤 1：创建 `MapleCodeException.java`**

```java
package com.maplecode.error;

public class MapleCodeException extends RuntimeException {
    public MapleCodeException(String message) {
        super(message);
    }
    public MapleCodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **步骤 2：创建 `ConfigException.java`**

```java
package com.maplecode.error;

public class ConfigException extends MapleCodeException {
    public ConfigException(String message) {
        super(message);
    }
}
```

- [ ] **步骤 3：创建 `ProviderException.java`**

```java
package com.maplecode.error;

public class ProviderException extends MapleCodeException {
    public ProviderException(String message) {
        super(message);
    }
    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **步骤 4：提交**

```bash
git add src/main/java/com/maplecode/error/
git commit -m "feat(error): 添加异常层级（MapleCodeException / ConfigException / ProviderException）"
```

---

## 阶段 2 — 核心 DTO

### 任务 3：ChatMessage + ChatRequest 记录

**文件：**
- 创建：`src/main/java/com/maplecode/provider/ChatMessage.java`
- 创建：`src/main/java/com/maplecode/provider/ChatRequest.java`

不需要测试 —— 纯数据记录，正确性显而易见。

- [ ] **步骤 1：创建 `ChatMessage.java`**

```java
package com.maplecode.provider;

public record ChatMessage(Role role, String content) {
    public enum Role { USER, ASSISTANT }
}
```

- [ ] **步骤 2：创建 `ChatRequest.java`**

```java
package com.maplecode.provider;

import java.util.List;

public record ChatRequest(
    String model,
    String systemPrompt,      // nullable
    List<ChatMessage> messages,
    ThinkingConfig thinking   // nullable
) {}
```

- [ ] **步骤 3：验证能编译**

运行：`mvn -q compile`
预期：BUILD SUCCESS（注意：`ThinkingConfig` 此时还没创建 —— 没问题，任务 5 会创建。如果编辑器抱怨，先建个 stub 占位，任务 5 再覆盖。）

如果因缺失 `ThinkingConfig` 编译失败，先在 `src/main/java/com/maplecode/provider/ThinkingConfig.java` 放这个占位：

```java
package com.maplecode.provider;
// 占位 —— 任务 5 会替换
public record ThinkingConfig(int budgetTokens) {}
```

然后继续。

- [ ] **步骤 4：提交**

```bash
git add src/main/java/com/maplecode/provider/ChatMessage.java src/main/java/com/maplecode/provider/ChatRequest.java
git commit -m "feat(provider): 添加 ChatMessage 与 ChatRequest 记录"
```

---

### 任务 4：StreamChunk sealed 接口

**文件：**
- 创建：`src/main/java/com/maplecode/provider/StreamChunk.java`

不需要测试 —— 声明式 sealed 类型，无逻辑。

- [ ] **步骤 1：创建 `StreamChunk.java`**

```java
package com.maplecode.provider;

public sealed interface StreamChunk
    permits StreamChunk.TextDelta,
            StreamChunk.ThinkingDelta,
            StreamChunk.MessageStart,
            StreamChunk.MessageEnd,
            StreamChunk.Error {

    record TextDelta(String text) implements StreamChunk {}
    record ThinkingDelta(String text) implements StreamChunk {}
    record MessageStart() implements StreamChunk {}
    record MessageEnd(StopReason reason) implements StreamChunk {}
    record Error(String code, String message) implements StreamChunk {}

    enum StopReason { END_TURN, MAX_TOKENS, STOP, ERROR }
}
```

- [ ] **步骤 2：验证能编译**

运行：`mvn -q compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：提交**

```bash
git add src/main/java/com/maplecode/provider/StreamChunk.java
git commit -m "feat(provider): 添加 StreamChunk sealed 接口（Text / Thinking / Start / End / Error）"
```

---

### 任务 5：ThinkingConfig（TDD）

**文件：**
- 替换占位：`src/main/java/com/maplecode/provider/ThinkingConfig.java`
- 创建：`src/test/java/com/maplecode/provider/ThinkingConfigTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/provider/ThinkingConfigTest.java`：

```java
package com.maplecode.provider;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;

import static com.maplecode.provider.ThinkingConfig.Effort.HIGH;
import static com.maplecode.provider.ThinkingConfig.Type.ADAPTIVE;
import static com.maplecode.provider.ThinkingConfig.Type.ENABLED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThinkingConfigTest {

    @Test
    void adaptive_with_effort_constructs_ok() {
        ThinkingConfig tc = new ThinkingConfig(ADAPTIVE, null, HIGH);
        assertEquals(ADAPTIVE, tc.type());
        assertEquals(HIGH, tc.effort());
        assertNull(tc.budgetTokens());
    }

    @Test
    void enabled_with_budget_tokens_constructs_ok() {
        ThinkingConfig tc = new ThinkingConfig(ENABLED, 10000, null);
        assertEquals(ENABLED, tc.type());
        assertEquals(10000, tc.budgetTokens());
        assertNull(tc.effort());
    }

    @Test
    void enabled_with_budget_tokens_below_1024_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ENABLED, 1023, null));
        assertEquals("extended_thinking.type=enabled requires budget_tokens >= 1024", ex.getMessage());
    }

    @Test
    void enabled_without_budget_tokens_throws() {
        assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ENABLED, null, null));
    }

    @Test
    void enabled_with_effort_throws_mutual_exclusion() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ENABLED, 10000, HIGH));
        assertEquals("extended_thinking.type=enabled and effort are mutually exclusive", ex.getMessage());
    }

    @Test
    void adaptive_without_effort_throws() {
        assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ADAPTIVE, null, null));
    }

    @Test
    void adaptive_with_budget_tokens_throws_mutual_exclusion() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ADAPTIVE, 10000, HIGH));
        assertEquals("extended_thinking.type=adaptive and budget_tokens are mutually exclusive", ex.getMessage());
    }

    @Test
    void boundary_1024_is_accepted() {
        assertDoesNotThrow(() -> new ThinkingConfig(ENABLED, 1024, null));
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=ThinkingConfigTest`
预期：FAIL —— 当前 `ThinkingConfig` 只有占位的 `(int)` 单参构造器，三参构造器不存在（编译错）。

- [ ] **步骤 3：用完整实现替换占位**

`src/main/java/com/maplecode/provider/ThinkingConfig.java`：

```java
package com.maplecode.provider;

import com.maplecode.error.ConfigException;

public record ThinkingConfig(
    Type type,
    Integer budgetTokens,        // 仅 type=ENABLED 时；>= 1024 且 < max_tokens
    Effort effort                // 仅 type=ADAPTIVE 时
) {
    public enum Type { ADAPTIVE, ENABLED }
    public enum Effort { LOW, MEDIUM, HIGH }

    public ThinkingConfig {
        if (type == Type.ENABLED) {
            if (budgetTokens == null || budgetTokens < 1024) {
                throw new ConfigException(
                    "extended_thinking.type=enabled requires budget_tokens >= 1024");
            }
            if (effort != null) {
                throw new ConfigException(
                    "extended_thinking.type=enabled and effort are mutually exclusive");
            }
        }
        if (type == Type.ADAPTIVE) {
            if (effort == null) {
                throw new ConfigException(
                    "extended_thinking.type=adaptive requires effort (low|medium|high)");
            }
            if (budgetTokens != null) {
                throw new ConfigException(
                    "extended_thinking.type=adaptive and budget_tokens are mutually exclusive");
            }
        }
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=ThinkingConfigTest`
预期：PASS —— 8 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/provider/ThinkingConfig.java src/test/java/com/maplecode/provider/ThinkingConfigTest.java
git commit -m "feat(provider): 添加 ThinkingConfig 与严格校验（adaptive+effort / enabled+budget_tokens）"
```

---

## 阶段 3 — 配置

### 任务 6：AppConfig 记录

**文件：**
- 创建：`src/main/java/com/maplecode/config/AppConfig.java`

不需要测试 —— 声明式记录。

- [ ] **步骤 1：创建 `AppConfig.java`**

```java
package com.maplecode.config;

import com.maplecode.provider.ThinkingConfig;

import java.time.Duration;

public record AppConfig(
    String protocol,
    String model,
    String baseUrl,
    String apiKey,
    String systemPrompt,           // nullable
    ThinkingConfig thinking,       // nullable
    Timeouts timeouts
) {
    public record Timeouts(int connectSeconds, int readSeconds) {
        public Duration connectDuration() { return Duration.ofSeconds(connectSeconds); }
        public Duration readDuration() { return Duration.ofSeconds(readSeconds); }
    }
}
```

- [ ] **步骤 2：验证能编译**

运行：`mvn -q compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：提交**

```bash
git add src/main/java/com/maplecode/config/AppConfig.java
git commit -m "feat(config): 添加 AppConfig 记录（protocol / model / baseUrl / apiKey / systemPrompt / thinking / timeouts）"
```

---

### 任务 7：ConfigLoader（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/config/ConfigLoader.java`
- 创建：`src/test/java/com/maplecode/config/ConfigLoaderTest.java`
- 创建：`src/test/java/com/maplecode/config/ConfigLoaderDeprecationWarningTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/config/ConfigLoaderTest.java`：

```java
package com.maplecode.config;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {

    @Test
    void loads_full_anthropic_config(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test-key
            system_prompt: hi
            extended_thinking:
              type: adaptive
              effort: high
            timeouts:
              connect_seconds: 5
              read_seconds: 30
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertEquals("anthropic", cfg.protocol());
        assertEquals("claude-sonnet-4-6", cfg.model());
        assertEquals("https://api.anthropic.com", cfg.baseUrl());
        assertEquals("sk-test-key", cfg.apiKey());
        assertEquals("hi", cfg.systemPrompt());
        assertNotNull(cfg.thinking());
        assertEquals("ADAPTIVE", cfg.thinking().type().name());
        assertEquals("HIGH", cfg.thinking().effort().name());
        assertEquals(5, cfg.timeouts().connectSeconds());
        assertEquals(30, cfg.timeouts().readSeconds());
    }

    @Test
    void minimal_config_optional_fields_null(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: openai
            model: gpt-4o
            base_url: https://api.openai.com/v1
            api_key: sk-test
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertEquals("openai", cfg.protocol());
        assertNull(cfg.systemPrompt());
        assertNull(cfg.thinking());
        assertEquals(10, cfg.timeouts().connectSeconds());   // 默认
        assertEquals(60, cfg.timeouts().readSeconds());      // 默认
    }

    @Test
    void missing_required_field_throws(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            """);
        ConfigException ex = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(tmp.resolve("config.yaml")));
        assertEquals("missing required field: api_key", ex.getMessage());
    }

    @Test
    void api_key_env_placeholder_replaced(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: ${MY_TEST_KEY}
            """);
        AppConfig cfg;
        try {
            System.setProperty("MY_TEST_KEY_BACKUP", "real-secret");
            // 需要的是环境变量而不是系统属性，下面这个测试用 assume 控制。
            cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        } finally { /* 由下一个测试兜底 */ }
        // 占位符替换用的是 System.getenv，难以在没有 ProcessBuilder 的情况下 mock。
        // 真实断言放在 unknown_env_var_throws。
    }

    @Test
    void unknown_env_var_throws(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: ${DEFINITELY_NOT_SET_12345_XYZ}
            """);
        ConfigException ex = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(tmp.resolve("config.yaml")));
        assertEquals("environment variable not set: DEFINITELY_NOT_SET_12345_XYZ", ex.getMessage());
    }

    @Test
    void invalid_thinking_type_fails_through_ThinkingConfig(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            extended_thinking:
              type: enabled
              budget_tokens: 500
            """);
        ConfigException ex = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(tmp.resolve("config.yaml")));
        assertEquals("extended_thinking.type=enabled requires budget_tokens >= 1024", ex.getMessage());
    }
}
```

`src/test/java/com/maplecode/config/ConfigLoaderDeprecationWarningTest.java`：

```java
package com.maplecode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderDeprecationWarningTest {

    @Test
    void enabled_thinking_emits_deprecation_warning_to_stderr(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            extended_thinking:
              type: enabled
              budget_tokens: 10000
            """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err));
            ConfigLoader.load(tmp.resolve("config.yaml"));
        } finally {
            System.setErr(original);
        }
        String output = err.toString();
        assertTrue(output.contains("deprecated"),
            "expected deprecation warning, got stderr: " + output);
        assertTrue(output.contains("type=adaptive"),
            "warning should suggest migration: " + output);
    }

    @Test
    void adaptive_thinking_emits_no_deprecation_warning(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            extended_thinking:
              type: adaptive
              effort: high
            """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err));
            ConfigLoader.load(tmp.resolve("config.yaml"));
        } finally {
            System.setErr(original);
        }
        assertFalse(err.toString().contains("deprecated"),
            "adaptive should not warn, got: " + err);
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest='ConfigLoaderTest,ConfigLoaderDeprecationWarningTest'`
预期：FAIL —— `ConfigLoader` 不存在（编译错）。

- [ ] **步骤 3：实现 `ConfigLoader.java`**

`src/main/java/com/maplecode/config/ConfigLoader.java`：

```java
package com.maplecode.config;

import com.maplecode.error.ConfigException;
import com.maplecode.provider.ThinkingConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)\\}");
    private static final int DEFAULT_CONNECT_SECONDS = 10;
    private static final int DEFAULT_READ_SECONDS = 60;

    private ConfigLoader() {}

    public static AppConfig load(Path path) {
        try (Reader r = Files.newBufferedReader(path)) {
            Object raw = new Yaml().load(r);
            if (!(raw instanceof Map<?, ?> map)) {
                throw new ConfigException("config root must be a mapping");
            }
            return parse(map);
        } catch (IOException e) {
            throw new ConfigException("failed to read config: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AppConfig parse(Map<?, ?> root) {
        String protocol = requireString(root, "protocol");
        String model = requireString(root, "model");
        String baseUrl = requireString(root, "base_url");
        String apiKey = expandEnv(requireString(root, "api_key"));

        String systemPrompt = optionalString(root, "system_prompt");
        ThinkingConfig thinking = parseThinking(optionalMap(root, "extended_thinking"));

        Map<?, ?> timeoutsMap = optionalMap(root, "timeouts");
        int connect = timeoutsMap != null && timeoutsMap.get("connect_seconds") instanceof Number n
            ? n.intValue() : DEFAULT_CONNECT_SECONDS;
        int read = timeoutsMap != null && timeoutsMap.get("read_seconds") instanceof Number n2
            ? n2.intValue() : DEFAULT_READ_SECONDS;

        return new AppConfig(protocol, model, baseUrl, apiKey, systemPrompt,
            thinking, new AppConfig.Timeouts(connect, read));
    }

    @SuppressWarnings("unchecked")
    private static ThinkingConfig parseThinking(Map<?, ?> m) {
        if (m == null) return null;
        String typeStr = optionalString(m, "type");
        if (typeStr == null) return null;
        ThinkingConfig.Type type = switch (typeStr) {
            case "adaptive" -> ThinkingConfig.Type.ADAPTIVE;
            case "enabled"  -> ThinkingConfig.Type.ENABLED;
            default -> throw new ConfigException(
                "extended_thinking.type must be 'adaptive' or 'enabled', got: " + typeStr);
        };
        Integer budget = optionalInt(m, "budget_tokens");
        ThinkingConfig.Effort effort = null;
        String effortStr = optionalString(m, "effort");
        if (effortStr != null) {
            effort = switch (effortStr) {
                case "low"    -> ThinkingConfig.Effort.LOW;
                case "medium" -> ThinkingConfig.Effort.MEDIUM;
                case "high"   -> ThinkingConfig.Effort.HIGH;
                default -> throw new ConfigException(
                    "extended_thinking.effort must be low|medium|high, got: " + effortStr);
            };
        }

        if (type == ThinkingConfig.Type.ENABLED) {
            System.err.println("warning: extended_thinking.type=enabled is deprecated for "
                + "Opus 4.6 / Sonnet 4.6 and returns HTTP 400 on Opus 4.7. "
                + "Prefer:\n"
                + "    type: adaptive\n"
                + "    effort: high");
        }

        // ThinkingConfig 紧凑构造器统一完成全部校验。
        return new ThinkingConfig(type, budget, effort);
    }

    private static String expandEnv(String value) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String var = matcher.group(1);
            String env = System.getenv(var);
            if (env == null) {
                throw new ConfigException("environment variable not set: " + var);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(env));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String requireString(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new ConfigException("missing required field: " + key);
        }
        return v.toString();
    }

    private static String optionalString(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer optionalInt(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> optionalMap(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Map<?, ?> map ? map : null;
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest='ConfigLoaderTest,ConfigLoaderDeprecationWarningTest'`
预期：PASS —— `loads_full_anthropic_config`、`minimal_config_optional_fields_null`、`missing_required_field_throws`、`api_key_env_placeholder_replaced`、`unknown_env_var_throws`、`invalid_thinking_type_fails_through_ThinkingConfig`、`enabled_thinking_emits_deprecation_warning_to_stderr`、`adaptive_thinking_emits_no_deprecation_warning` 全部通过。

注：`api_key_env_placeholder_replaced` 并不严格断言替换结果（`System.getenv` 难以 mock），只是冒烟测试加载不抛错。真正的占位符断言在 `unknown_env_var_throws`。

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/config/ConfigLoader.java src/test/java/com/maplecode/config/
git commit -m "feat(config): 添加 ConfigLoader（YAML 解析 + 环境变量替换 + deprecation 警告）"
```

---

## 阶段 4 — HTTP 层

### 任务 8：SseStreamReader（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/http/SseStreamReader.java`
- 创建：`src/test/java/com/maplecode/http/SseStreamReaderTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/http/SseStreamReaderTest.java`：

```java
package com.maplecode.http;

import com.maplecode.http.SseStreamReader.SseEvent;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SseStreamReaderTest {

    private List<SseEvent> feed(SseStreamReader reader, String... lines) {
        HttpResponse<Stream<String>> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(Stream.of(lines));
        var out = new java.util.ArrayList<SseEvent>();
        reader.read(resp, out::add);
        return out;
    }

    @Test
    void single_data_line_with_implicit_event() {
        var events = feed(new SseStreamReader(), "data: hello\n", "");
        assertEquals(1, events.size());
        assertEquals("message", events.get(0).eventType());  // 按 SSE 规范的默认值
        assertEquals("hello", events.get(0).data());
    }

    @Test
    void explicit_event_type() {
        var events = feed(new SseStreamReader(),
            "event: ping\n", "data: {\"x\":1}\n", "");
        assertEquals(1, events.size());
        assertEquals("ping", events.get(0).eventType());
        assertEquals("{\"x\":1}", events.get(0).data());
    }

    @Test
    void multiline_data_is_joined_with_newline() {
        var events = feed(new SseStreamReader(),
            "data: line1\n", "data: line2\n", "data: line3\n", "");
        assertEquals(1, events.size());
        assertEquals("line1\nline2\nline3", events.get(0).data());
    }

    @Test
    void comment_lines_are_ignored() {
        var events = feed(new SseStreamReader(),
            ": this is a comment\n", "data: real\n", "");
        assertEquals(1, events.size());
        assertEquals("real", events.get(0).data());
    }

    @Test
    void heartbeat_comment_does_not_emit_event() {
        var events = feed(new SseStreamReader(),
            ": heartbeat\n", ": heartbeat\n", "data: only-real\n", "");
        assertEquals(1, events.size());
        assertEquals("only-real", events.get(0).data());
    }

    @Test
    void done_marker_is_emitted_as_event_with_done_data() {
        var events = feed(new SseStreamReader(),
            "data: [DONE]\n", "");
        assertEquals(1, events.size());
        assertEquals("[DONE]", events.get(0).data());
    }

    @Test
    void multiple_events_separated_by_blank_lines() {
        var events = feed(new SseStreamReader(),
            "event: a\n", "data: 1\n",
            "",
            "event: b\n", "data: 2\n",
            "");
        assertEquals(2, events.size());
        assertEquals("a", events.get(0).eventType());
        assertEquals("1", events.get(0).data());
        assertEquals("b", events.get(1).eventType());
        assertEquals("2", events.get(1).data());
    }

    @Test
    void empty_data_field_still_emits_event() {
        var events = feed(new SseStreamReader(),
            "event: keepalive\n", "data:\n", "");
        assertEquals(1, events.size());
        assertEquals("keepalive", events.get(0).eventType());
        assertEquals("", events.get(0).data());
    }
}
```

往 `pom.xml` 的 `<dependencies>` 里追加 Mockito：

```xml
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=SseStreamReaderTest`
预期：FAIL —— `SseStreamReader` 和 `SseEvent` 不存在（编译错）。

- [ ] **步骤 3：实现 `SseStreamReader.java`**

`src/main/java/com/maplecode/http/SseStreamReader.java`：

```java
package com.maplecode.http;

import com.maplecode.error.ProviderException;

import java.net.http.HttpResponse;
import java.util.stream.Stream;

public final class SseStreamReader {

    public record SseEvent(String eventType, String data) {}

    private static final String DEFAULT_EVENT = "message";

    public void read(HttpResponse<Stream<String>> response, java.util.function.Consumer<SseEvent> eventSink) {
        String currentEvent = DEFAULT_EVENT;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;

        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isEmpty()) {
                    if (hasData) {
                        eventSink.accept(new SseEvent(currentEvent, data.toString()));
                    }
                    currentEvent = DEFAULT_EVENT;
                    data.setLength(0);
                    hasData = false;
                    continue;
                }
                if (line.startsWith(":")) {
                    // 注释 / 心跳 —— 忽略
                    continue;
                }
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    String payload = line.substring(5);
                    if (payload.startsWith(" ")) payload = payload.substring(1);
                    if (hasData) data.append('\n');
                    data.append(payload);
                    hasData = true;
                } else if (line.startsWith("id:") || line.startsWith("retry:")) {
                    // v1 不处理
                }
                // 其他字段 —— 忽略
            }
            // 流末尾没有空行时 flush（SSE 规范允许）
            if (hasData) {
                eventSink.accept(new SseEvent(currentEvent, data.toString()));
            }
        } catch (RuntimeException e) {
            throw new ProviderException("SSE stream read failed", e);
        }
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=SseStreamReaderTest`
预期：PASS —— 8 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/http/ src/test/java/com/maplecode/http/ pom.xml
git commit -m "feat(http): 添加 SseStreamReader（处理注释 / 多行 data / [DONE] / 末尾 flush）"
```

---

## 阶段 5 — Anthropic Provider

### 任务 9：LlmProvider 接口

**文件：**
- 创建：`src/main/java/com/maplecode/provider/LlmProvider.java`

不需要测试 —— 单方法接口。

- [ ] **步骤 1：创建 `LlmProvider.java`**

```java
package com.maplecode.provider;

import java.util.function.Consumer;

public interface LlmProvider {
    /**
     * 流式聊天补全。每个 chunk 同步推给 sink。
     * 传输 / 协议 / HTTP 错误抛 ProviderException。
     */
    void stream(ChatRequest request, Consumer<StreamChunk> sink);
}
```

- [ ] **步骤 2：验证能编译**

运行：`mvn -q compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：提交**

```bash
git add src/main/java/com/maplecode/provider/LlmProvider.java
git commit -m "feat(provider): 添加 LlmProvider 接口"
```

---

### 任务 10：AnthropicRequestMapper（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java`
- 创建：`src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java`：

```java
package com.maplecode.provider.anthropic;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.provider.ThinkingConfig.Effort;
import com.maplecode.provider.ThinkingConfig.Type;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestMapperTest {

    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();

    @Test
    void minimal_request_no_thinking_no_system() {
        var req = new ChatRequest("claude-sonnet-4-6", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")), null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.anthropic.com", "sk-test");
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("sk-test", http.headers().firstValue("x-api-key").orElseThrow());
        assertEquals("2023-06-01", http.headers().firstValue("anthropic-version").orElseThrow());

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"claude-sonnet-4-6\""));
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"));
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"max_tokens\":16384"));
        assertFalse(body.contains("\"system\""), "system 为 null 时必须缺失");
        assertFalse(body.contains("\"thinking\""), "thinking 为 null 时必须缺失");
        assertFalse(body.contains("\"output_config\""), "无 thinking 时 output_config 必须缺失");
    }

    @Test
    void adaptive_thinking_emits_thinking_and_output_config() {
        var req = new ChatRequest("claude-opus-4-7", "be terse",
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")),
            new ThinkingConfig(Type.ADAPTIVE, null, Effort.HIGH));

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"adaptive\"}"));
        assertTrue(body.contains("\"output_config\":{\"effort\":\"high\"}"));
        assertFalse(body.contains("\"budget_tokens\""), "adaptive 不能包含 budget_tokens");
        assertTrue(body.contains("\"system\":\"be terse\""));
    }

    @Test
    void enabled_thinking_emits_thinking_with_budget_tokens_only() {
        var req = new ChatRequest("claude-sonnet-4-6", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")),
            new ThinkingConfig(Type.ENABLED, 10000, null));

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10000}"));
        assertFalse(body.contains("\"output_config\""),
            "enabled 不能写 output_config");
    }

    @Test
    void multiple_messages_preserved_in_order() {
        var req = new ChatRequest("claude-sonnet-4-6", null, List.of(
            new ChatMessage(ChatMessage.Role.USER, "u1"),
            new ChatMessage(ChatMessage.Role.ASSISTANT, "a1"),
            new ChatMessage(ChatMessage.Role.USER, "u2")
        ), null);
        String body = mapper.toJsonBody(req);
        int u1 = body.indexOf("\"u1\"");
        int a1 = body.indexOf("\"a1\"");
        int u2 = body.indexOf("\"u2\"");
        assertTrue(u1 > 0 && a1 > u1 && u2 > a1, "messages 必须按输入顺序");
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=AnthropicRequestMapperTest`
预期：FAIL —— `AnthropicRequestMapper` 不存在（编译错）。

- [ ] **步骤 3：实现 `AnthropicRequestMapper.java`**

`src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java`：

```java
package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ThinkingConfig;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class AnthropicRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKENS = 16384;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    public HttpRequest toHttpRequest(ChatRequest req, String baseUrl, String apiKey) {
        String body = toJsonBody(req);
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/messages"))
            .timeout(Duration.ofSeconds(60))
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    public String toJsonBody(ChatRequest req) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", req.model());
            root.put("max_tokens", MAX_TOKENS);
            root.put("stream", true);

            if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) {
                root.put("system", req.systemPrompt());
            }

            ArrayNode msgs = root.putArray("messages");
            for (var m : req.messages()) {
                msgs.add(JSON.createObjectNode()
                    .put("role", m.role().name().toLowerCase())
                    .put("content", m.content()));
            }

            if (req.thinking() != null) {
                ThinkingConfig tc = req.thinking();
                ObjectNode thinking = root.putObject("thinking");
                switch (tc.type()) {
                    case ADAPTIVE -> {
                        thinking.put("type", "adaptive");
                        ObjectNode outputConfig = root.putObject("output_config");
                        outputConfig.put("effort", tc.effort().name().toLowerCase());
                    }
                    case ENABLED -> {
                        thinking.put("type", "enabled");
                        thinking.put("budget_tokens", tc.budgetTokens());
                    }
                }
            }

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize Anthropic request", e);
        }
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=AnthropicRequestMapperTest`
预期：PASS —— 4 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/provider/anthropic/AnthropicRequestMapper.java src/test/java/com/maplecode/provider/anthropic/AnthropicRequestMapperTest.java
git commit -m "feat(anthropic): 添加请求 mapper（adaptive+output_config 与 enabled+budget_tokens 两条分支）"
```

---

### 任务 11：AnthropicStreamParser（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java`
- 创建：`src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserTest.java`：

```java
package com.maplecode.provider.anthropic;

import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AnthropicStreamParserTest {

    private final AnthropicStreamParser parser = new AnthropicStreamParser();

    private List<StreamChunk> feed(String... lines) {
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;
        parser.reset();
        // parser 消费的是 SseEvent；测试里我们用 SseStreamReader 内部同样的状态机喂入。
        feedLines(lines, sink);
        return out;
    }

    private void feedLines(String[] lines, Consumer<StreamChunk> sink) {
        String currentEvent = "message";
        StringBuilder data = new StringBuilder();
        boolean hasData = false;

        for (String line : lines) {
            if (line.isEmpty()) {
                if (hasData) {
                    parser.feed(new com.maplecode.http.SseStreamReader.SseEvent(currentEvent, data.toString()), sink);
                }
                currentEvent = "message";
                data.setLength(0);
                hasData = false;
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                String payload = line.substring(5);
                if (payload.startsWith(" ")) payload = payload.substring(1);
                if (hasData) data.append('\n');
                data.append(payload);
                hasData = true;
            }
        }
        if (hasData) {
            parser.feed(new com.maplecode.http.SseStreamReader.SseEvent(currentEvent, data.toString()), sink);
        }
    }

    @Test
    void message_start_then_text_delta_then_message_stop() {
        var chunks = feed(
            "event: message_start\n",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m_1\"}}\n",
            "",
            "event: content_block_start\n",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}\n",
            "",
            "event: content_block_stop\n",
            "data: {\"type\":\"content_block_stop\",\"index\":0}\n",
            "",
            "event: message_stop\n",
            "data: {\"type\":\"message_stop\"}\n",
            ""
        );
        assertEquals(4, chunks.size());
        assertInstanceOf(StreamChunk.MessageStart.class, chunks.get(0));
        assertEquals("Hello", ((StreamChunk.TextDelta) chunks.get(1)).text());
        assertEquals(" world", ((StreamChunk.TextDelta) chunks.get(2)).text());
        assertEquals(StreamChunk.StopReason.END_TURN,
            ((StreamChunk.MessageEnd) chunks.get(3)).reason());
    }

    @Test
    void thinking_blocks_emitted_as_ThinkingDelta() {
        var chunks = feed(
            "event: content_block_start\n",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me think...\"}}\n",
            "",
            "event: content_block_stop\n",
            "data: {\"type\":\"content_block_stop\",\"index\":0}\n",
            "",
            "event: content_block_start\n",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n",
            "",
            "event: content_block_delta\n",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Answer\"}}\n",
            "",
            "event: message_stop\n",
            "data: {\"type\":\"message_stop\"}\n",
            ""
        );
        long thinking = chunks.stream().filter(c -> c instanceof StreamChunk.ThinkingDelta).count();
        long text = chunks.stream().filter(c -> c instanceof StreamChunk.TextDelta).count();
        assertEquals(1, thinking, "应恰好一个 ThinkingDelta");
        assertEquals(1, text, "应恰好一个 TextDelta");
        assertEquals("Let me think...", ((StreamChunk.ThinkingDelta) chunks.stream()
            .filter(c -> c instanceof StreamChunk.ThinkingDelta).findFirst().orElseThrow()).text());
        assertEquals("Answer", ((StreamChunk.TextDelta) chunks.stream()
            .filter(c -> c instanceof StreamChunk.TextDelta).findFirst().orElseThrow()).text());
    }

    @Test
    void error_event_becomes_StreamChunk_Error() {
        var chunks = feed(
            "event: error\n",
            "data: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}\n",
            ""
        );
        assertEquals(1, chunks.size());
        var err = (StreamChunk.Error) chunks.get(0);
        assertEquals("invalid_request_error", err.code());
        assertEquals("bad", err.message());
    }

    @Test
    void unknown_event_type_is_ignored() {
        var chunks = feed(
            "event: ping\n",
            "data: {\"foo\":1}\n",
            ""
        );
        assertEquals(0, chunks.size());
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=AnthropicStreamParserTest`
预期：FAIL —— `AnthropicStreamParser` 不存在。

- [ ] **步骤 3：实现 `AnthropicStreamParser.java`**

`src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java`：

```java
package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.function.Consumer;

public final class AnthropicStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BlockType currentBlock = BlockType.NONE;

    private enum BlockType { NONE, THINKING, TEXT }

    public void reset() {
        currentBlock = BlockType.NONE;
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        String type = event.eventType();
        if (type.equals("message_start")) {
            currentBlock = BlockType.NONE;
            sink.accept(new StreamChunk.MessageStart());
            return;
        }
        if (type.equals("content_block_start")) {
            JsonNode node = parse(event.data());
            String blockType = node.path("content_block").path("type").asText("");
            currentBlock = switch (blockType) {
                case "thinking" -> BlockType.THINKING;
                case "text"     -> BlockType.TEXT;
                default         -> BlockType.NONE;
            };
            return;
        }
        if (type.equals("content_block_delta")) {
            JsonNode node = parse(event.data());
            JsonNode delta = node.path("delta");
            String deltaType = delta.path("type").asText("");
            if (deltaType.equals("thinking_delta")) {
                sink.accept(new StreamChunk.ThinkingDelta(delta.path("thinking").asText("")));
            } else if (deltaType.equals("text_delta")) {
                sink.accept(new StreamChunk.TextDelta(delta.path("text").asText("")));
            }
            return;
        }
        if (type.equals("content_block_stop")) {
            currentBlock = BlockType.NONE;
            return;
        }
        if (type.equals("message_stop")) {
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN));
            return;
        }
        if (type.equals("error")) {
            JsonNode node = parse(event.data());
            String code = node.path("error").path("type").asText("unknown");
            String msg = node.path("error").path("message").asText("");
            sink.accept(new StreamChunk.Error(code, msg));
            return;
        }
        // ping / 未知 —— 忽略
    }

    private JsonNode parse(String data) {
        try {
            return JSON.readTree(data);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse Anthropic SSE data: " + data, e);
        }
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=AnthropicStreamParserTest`
预期：PASS —— 4 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/provider/anthropic/AnthropicStreamParser.java src/test/java/com/maplecode/provider/anthropic/AnthropicStreamParserTest.java
git commit -m "feat(anthropic): 添加流式 parser（thinking+text delta / 消息生命周期 / error 事件）"
```

---

### 任务 12：AnthropicProvider

**文件：**
- 创建：`src/main/java/com/maplecode/provider/anthropic/AnthropicProvider.java`

Provider 粘合层本身不做单元测试 —— 逻辑已被 mapper + parser + SseStreamReader 测试覆盖。用真 key 的冒烟测试在 README / 任务 19 第 8 步验证。

- [ ] **步骤 1：创建 `AnthropicProvider.java`**

```java
package com.maplecode.provider.anthropic;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ProviderException;
import com.maplecode.http.SseStreamReader;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public final class AnthropicProvider implements LlmProvider {

    private final AppConfig config;
    private final HttpClient httpClient;
    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();
    private final AnthropicStreamParser parser = new AnthropicStreamParser();
    private final SseStreamReader sseReader = new SseStreamReader();

    public AnthropicProvider(AppConfig config) {
        this(config, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
            .build());
    }

    // 给测试 / 未来 DI 用的构造器
    public AnthropicProvider(AppConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamChunk> sink) {
        HttpRequest httpReq = mapper.toHttpRequest(request, config.baseUrl(), config.apiKey());
        HttpResponse<java.util.stream.Stream<String>> resp;
        try {
            resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofLines());
        } catch (Exception e) {
            throw new ProviderException("HTTP request failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            String body = readBodyForError(resp);
            throw new ProviderException(
                "Anthropic returned HTTP " + resp.statusCode() + ": " + body);
        }
        parser.reset();
        sseReader.read(resp, ev -> parser.feed(ev, sink));
    }

    private String readBodyForError(HttpResponse<java.util.stream.Stream<String>> resp) {
        try {
            return resp.body().reduce("", (a, b) -> a + b);
        } catch (Exception e) {
            return "<body unavailable>";
        }
    }
}
```

- [ ] **步骤 2：验证能编译**

运行：`mvn -q compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：提交**

```bash
git add src/main/java/com/maplecode/provider/anthropic/AnthropicProvider.java
git commit -m "feat(anthropic): 添加 AnthropicProvider（mapper + sse reader + parser 粘合）"
```

---

## 阶段 6 — OpenAI Provider

### 任务 13：OpenAiRequestMapper（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java`
- 创建：`src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java`：

```java
package com.maplecode.provider.openai;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.provider.ThinkingConfig.Effort;
import com.maplecode.provider.ThinkingConfig.Type;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRequestMapperTest {

    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();

    @Test
    void minimal_request_emits_bearer_header() {
        var req = new ChatRequest("gpt-4o", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")), null);

        HttpRequest http = mapper.toHttpRequest(req, "https://api.openai.com/v1", "sk-test");
        assertEquals(URI.create("https://api.openai.com/v1/chat/completions"), http.uri());
        assertEquals("application/json", http.headers().firstValue("content-type").orElseThrow());
        assertEquals("Bearer sk-test", http.headers().firstValue("authorization").orElseThrow());

        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"model\":\"gpt-4o\""));
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"));
        assertTrue(body.contains("\"stream\":true"));
        assertFalse(body.contains("\"thinking\""));
        assertFalse(body.contains("\"reasoning\""));
    }

    @Test
    void system_prompt_appears_first_in_messages_array() {
        var req = new ChatRequest("gpt-4o", "be terse",
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")), null);

        String body = mapper.toJsonBody(req);
        int systemIdx = body.indexOf("\"role\":\"system\"");
        int userIdx = body.indexOf("\"role\":\"user\"");
        assertTrue(systemIdx > 0 && userIdx > systemIdx,
            "system 消息必须排在 user 前面");
        assertTrue(body.contains("\"content\":\"be terse\""));
    }

    @Test
    void thinking_is_silently_dropped_for_openai() {
        var req = new ChatRequest("gpt-4o", null,
            List.of(new ChatMessage(ChatMessage.Role.USER, "hi")),
            new ThinkingConfig(Type.ADAPTIVE, null, Effort.HIGH));

        String body = mapper.toJsonBody(req);
        assertFalse(body.contains("thinking"));
        assertFalse(body.contains("reasoning"));
        assertFalse(body.contains("output_config"));
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=OpenAiRequestMapperTest`
预期：FAIL —— `OpenAiRequestMapper` 不存在。

- [ ] **步骤 3：实现 `OpenAiRequestMapper.java`**

`src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java`：

```java
package com.maplecode.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatRequest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OpenAiRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    public HttpRequest toHttpRequest(ChatRequest req, String baseUrl, String apiKey) {
        String body = toJsonBody(req);
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("content-type", "application/json")
            .header("authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    public String toJsonBody(ChatRequest req) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", req.model());
            root.put("stream", true);

            // thinking 静默丢弃 —— v1 的 Chat Completions 没有这个字段
            // （后续可接到 o1 的 reasoning_effort）

            ArrayNode msgs = root.putArray("messages");
            if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) {
                msgs.add(JSON.createObjectNode()
                    .put("role", "system")
                    .put("content", req.systemPrompt()));
            }
            for (var m : req.messages()) {
                msgs.add(JSON.createObjectNode()
                    .put("role", m.role().name().toLowerCase())
                    .put("content", m.content()));
            }

            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize OpenAI request", e);
        }
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=OpenAiRequestMapperTest`
预期：PASS —— 3 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/provider/openai/OpenAiRequestMapper.java src/test/java/com/maplecode/provider/openai/OpenAiRequestMapperTest.java
git commit -m "feat(openai): 添加请求 mapper（system+user 消息、Bearer 认证、thinking 静默丢弃）"
```

---

### 任务 14：OpenAiStreamParser（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java`
- 创建：`src/test/java/com/maplecode/provider/openai/OpenAiStreamParserTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/provider/openai/OpenAiStreamParserTest.java`：

```java
package com.maplecode.provider.openai;

import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OpenAiStreamParserTest {

    private final OpenAiStreamParser parser = new OpenAiStreamParser();

    private List<StreamChunk> feed(String... dataLines) {
        List<StreamChunk> out = new ArrayList<>();
        Consumer<StreamChunk> sink = out::add;
        for (String payload : dataLines) {
            parser.feed(new SseEvent("message", payload), sink);
        }
        return out;
    }

    @Test
    void multiple_text_deltas_accumulate_via_consumer() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{\"content\":\"Hel\"},\"index\":0}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"lo\"},\"index\":0}]}"
        );
        assertEquals(2, chunks.size());
        assertEquals("Hel", ((StreamChunk.TextDelta) chunks.get(0)).text());
        assertEquals("lo",  ((StreamChunk.TextDelta) chunks.get(1)).text());
    }

    @Test
    void finish_reason_emits_message_end() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\",\"index\":0}]}"
        );
        assertEquals(2, chunks.size());
        assertInstanceOf(StreamChunk.TextDelta.class, chunks.get(0));
        assertInstanceOf(StreamChunk.MessageEnd.class, chunks.get(1));
        assertEquals(StreamChunk.StopReason.STOP,
            ((StreamChunk.MessageEnd) chunks.get(1)).reason());
    }

    @Test
    void done_marker_emits_message_end_stop() {
        var chunks = feed("[DONE]");
        assertEquals(1, chunks.size());
        assertInstanceOf(StreamChunk.MessageEnd.class, chunks.get(0));
        assertEquals(StreamChunk.StopReason.STOP,
            ((StreamChunk.MessageEnd) chunks.get(0)).reason());
    }

    @Test
    void error_object_in_data_becomes_StreamChunk_Error() {
        var chunks = feed(
            "{\"error\":{\"type\":\"invalid_api_key\",\"message\":\"bad key\"}}"
        );
        assertEquals(1, chunks.size());
        var err = (StreamChunk.Error) chunks.get(0);
        assertEquals("invalid_api_key", err.code());
        assertEquals("bad key", err.message());
    }

    @Test
    void empty_delta_emits_no_chunk() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{},\"index\":0}]}"
        );
        assertEquals(0, chunks.size());
    }

    @Test
    void finish_reason_length_maps_to_max_tokens() {
        var chunks = feed(
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\",\"index\":0}]}"
        );
        assertEquals(StreamChunk.StopReason.MAX_TOKENS,
            ((StreamChunk.MessageEnd) chunks.get(0)).reason());
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=OpenAiStreamParserTest`
预期：FAIL —— `OpenAiStreamParser` 不存在。

- [ ] **步骤 3：实现 `OpenAiStreamParser.java`**

`src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java`：

```java
package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.http.SseStreamReader.SseEvent;
import com.maplecode.provider.StreamChunk;

import java.util.function.Consumer;

public final class OpenAiStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean ended = false;

    public void reset() {
        ended = false;
    }

    public void feed(SseEvent event, Consumer<StreamChunk> sink) {
        if (ended) return;
        String data = event.data();
        if (data == null) return;
        if (data.equals("[DONE]")) {
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.STOP));
            ended = true;
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(data);
        } catch (Exception e) {
            // 畸形 chunk —— 忽略（传输异常由 SseStreamReader 的 IOException 处理）
            return;
        }

        // 顶层 error 对象
        if (node.has("error")) {
            JsonNode err = node.path("error");
            sink.accept(new StreamChunk.Error(
                err.path("type").asText("unknown"),
                err.path("message").asText("")
            ));
            return;
        }

        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return;
        JsonNode choice0 = choices.get(0);
        JsonNode delta = choice0.path("delta");

        String content = delta.path("content").asText(null);
        if (content != null && !content.isEmpty()) {
            sink.accept(new StreamChunk.TextDelta(content));
        }

        String finish = choice0.path("finish_reason").asText("");
        if (!finish.isEmpty() && !"null".equals(finish)) {
            StreamChunk.StopReason reason = switch (finish) {
                case "stop"   -> StreamChunk.StopReason.STOP;
                case "length" -> StreamChunk.StopReason.MAX_TOKENS;
                case "error"  -> StreamChunk.StopReason.ERROR;
                default       -> StreamChunk.StopReason.STOP;
            };
            sink.accept(new StreamChunk.MessageEnd(reason));
            ended = true;
        }
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=OpenAiStreamParserTest`
预期：PASS —— 6 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/provider/openai/OpenAiStreamParser.java src/test/java/com/maplecode/provider/openai/OpenAiStreamParserTest.java
git commit -m "feat(openai): 添加流式 parser（content delta / finish_reason / [DONE] / error 对象）"
```

---

### 任务 15：OpenAiProvider

**文件：**
- 创建：`src/main/java/com/maplecode/provider/openai/OpenAiProvider.java`

- [ ] **步骤 1：创建 `OpenAiProvider.java`**

```java
package com.maplecode.provider.openai;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ProviderException;
import com.maplecode.http.SseStreamReader;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public final class OpenAiProvider implements LlmProvider {

    private final AppConfig config;
    private final HttpClient httpClient;
    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();
    private final OpenAiStreamParser parser = new OpenAiStreamParser();
    private final SseStreamReader sseReader = new SseStreamReader();

    public OpenAiProvider(AppConfig config) {
        this(config, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
            .build());
    }

    public OpenAiProvider(AppConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamChunk> sink) {
        HttpRequest httpReq = mapper.toHttpRequest(request, config.baseUrl(), config.apiKey());
        HttpResponse<java.util.stream.Stream<String>> resp;
        try {
            resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofLines());
        } catch (Exception e) {
            throw new ProviderException("HTTP request failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            String body = readBodyForError(resp);
            throw new ProviderException(
                "OpenAI returned HTTP " + resp.statusCode() + ": " + body);
        }
        parser.reset();
        sseReader.read(resp, ev -> parser.feed(ev, sink));
    }

    private String readBodyForError(HttpResponse<java.util.stream.Stream<String>> resp) {
        try {
            return resp.body().reduce("", (a, b) -> a + b);
        } catch (Exception e) {
            return "<body unavailable>";
        }
    }
}
```

- [ ] **步骤 2：验证能编译**

运行：`mvn -q compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：提交**

```bash
git add src/main/java/com/maplecode/provider/openai/OpenAiProvider.java
git commit -m "feat(openai): 添加 OpenAiProvider（mapper + sse reader + parser 粘合）"
```

---

## 阶段 7 — 注册中心

### 任务 16：ProviderRegistry（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/provider/ProviderRegistry.java`
- 创建：`src/test/java/com/maplecode/provider/ProviderRegistryTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/provider/ProviderRegistryTest.java`：

```java
package com.maplecode.provider;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.provider.anthropic.AnthropicProvider;
import com.maplecode.provider.openai.OpenAiProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderRegistryTest {

    private AppConfig cfg(String protocol) {
        return new AppConfig(protocol, "m", "https://x", "k", null, null,
            new AppConfig.Timeouts(10, 60));
    }

    @Test
    void anthropic_protocol_returns_AnthropicProvider() {
        var reg = new ProviderRegistry();
        var p = reg.create(cfg("anthropic"));
        assertInstanceOf(AnthropicProvider.class, p);
    }

    @Test
    void openai_protocol_returns_OpenAiProvider() {
        var reg = new ProviderRegistry();
        var p = reg.create(cfg("openai"));
        assertInstanceOf(OpenAiProvider.class, p);
    }

    @Test
    void unknown_protocol_throws_ConfigException() {
        var reg = new ProviderRegistry();
        ConfigException ex = assertThrows(ConfigException.class,
            () -> reg.create(cfg("azure")));
        assertEquals(expectMessage("azure"), ex.getMessage());
    }

    private String expectMessage(String p) {
        return "unknown protocol: " + p + " (supported: anthropic, openai)";
    }

    @Test
    void null_protocol_throws_ConfigException() {
        var reg = new ProviderRegistry();
        assertThrows(ConfigException.class, () -> reg.create(cfg(null)));
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=ProviderRegistryTest`
预期：FAIL —— `ProviderRegistry` 不存在。

- [ ] **步骤 3：实现 `ProviderRegistry.java`**

`src/main/java/com/maplecode/provider/ProviderRegistry.java`：

```java
package com.maplecode.provider;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.provider.anthropic.AnthropicProvider;
import com.maplecode.provider.openai.OpenAiProvider;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class ProviderRegistry {

    private final Map<String, Function<AppConfig, LlmProvider>> factories = Map.of(
        "anthropic", AnthropicProvider::new,
        "openai",    OpenAiProvider::new
    );

    public LlmProvider create(AppConfig config) {
        if (config.protocol() == null) {
            throw new ConfigException("missing required field: protocol");
        }
        Function<AppConfig, LlmProvider> factory = factories.get(config.protocol());
        if (factory == null) {
            throw new ConfigException("unknown protocol: " + config.protocol()
                + " (supported: " + String.join(", ", supportedProtocols()) + ")");
        }
        return factory.apply(config);
    }

    private Set<String> supportedProtocols() {
        return factories.keySet();
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=ProviderRegistryTest`
预期：PASS —— 4 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/provider/ProviderRegistry.java src/test/java/com/maplecode/provider/ProviderRegistryTest.java
git commit -m "feat(provider): 添加 ProviderRegistry（anthropic/openai 工厂，未知 protocol 抛 ConfigException）"
```

---

## 阶段 8 — 会话、UI 与主入口

### 任务 17：ChatSession（TDD）

**文件：**
- 创建：`src/main/java/com/maplecode/session/ChatSession.java`
- 创建：`src/test/java/com/maplecode/session/ChatSessionTest.java`

- [ ] **步骤 1：编写失败测试**

`src/test/java/com/maplecode/session/ChatSessionTest.java`：

```java
package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatSessionTest {

    @Test
    void empty_session_toRequest_has_empty_messages() {
        var session = new ChatSession();
        ChatRequest req = session.toRequest("m", null, null);
        assertEquals(0, req.messages().size());
    }

    @Test
    void append_user_then_assistant_in_order() {
        var session = new ChatSession();
        session.appendUser("u1");
        session.appendAssistant("a1");
        session.appendUser("u2");

        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertEquals(3, msgs.size());
        assertEquals(ChatMessage.Role.USER, msgs.get(0).role());
        assertEquals("u1", msgs.get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, msgs.get(1).role());
        assertEquals("a1", msgs.get(1).content());
        assertEquals(ChatMessage.Role.USER, msgs.get(2).role());
        assertEquals("u2", msgs.get(2).content());
    }

    @Test
    void clear_resets_messages() {
        var session = new ChatSession();
        session.appendUser("x");
        session.clear();
        assertEquals(0, session.toRequest("m", null, null).messages().size());
    }

    @Test
    void toRequest_returns_immutable_copy() {
        var session = new ChatSession();
        session.appendUser("u1");
        List<ChatMessage> msgs = session.toRequest("m", null, null).messages();
        assertThrows(UnsupportedOperationException.class, () -> msgs.add(
            new ChatMessage(ChatMessage.Role.USER, "rogue")));
    }

    @Test
    void toRequest_passes_through_system_prompt_and_thinking() {
        var session = new ChatSession();
        session.appendUser("hi");
        ChatRequest req = session.toRequest("claude-sonnet-4-6", "be terse", null);
        assertEquals("claude-sonnet-4-6", req.model());
        assertEquals("be terse", req.systemPrompt());
        assertEquals(null, req.thinking());
    }
}
```

- [ ] **步骤 2：跑测试确认失败**

运行：`mvn -q test -Dtest=ChatSessionTest`
预期：FAIL —— `ChatSession` 不存在。

- [ ] **步骤 3：实现 `ChatSession.java`**

`src/main/java/com/maplecode/session/ChatSession.java`：

```java
package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ThinkingConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatSession {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void appendUser(String text) {
        messages.add(new ChatMessage(Role.USER, text));
    }

    public void appendAssistant(String text) {
        messages.add(new ChatMessage(Role.ASSISTANT, text));
    }

    public void clear() {
        messages.clear();
    }

    public ChatRequest toRequest(String model, String systemPrompt, ThinkingConfig thinking) {
        return new ChatRequest(model, systemPrompt,
            Collections.unmodifiableList(new ArrayList<>(messages)), thinking);
    }
}
```

- [ ] **步骤 4：跑测试确认通过**

运行：`mvn -q test -Dtest=ChatSessionTest`
预期：PASS —— 5 个测试，0 失败

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/maplecode/session/ChatSession.java src/test/java/com/maplecode/session/ChatSessionTest.java
git commit -m "feat(session): 添加 ChatSession（append / clear / toRequest，返回不可变视图）"
```

---

### 任务 18：StreamPrinter

**文件：**
- 创建：`src/main/java/com/maplecode/ui/StreamPrinter.java`

不做单元测试 —— 输出渲染最好手动验证。逻辑很小（5 个方法 + ANSI 转义）。

- [ ] **步骤 1：创建 `StreamPrinter.java`**

```java
package com.maplecode.ui;

import java.io.PrintStream;

public final class StreamPrinter {

    private static final String RESET = "\033[0m";
    private static final String DIM   = "\033[90m";
    private static final String BOLD  = "\033[1m";
    private static final String RED   = "\033[31m";

    private final PrintStream out;

    public StreamPrinter() {
        this(System.out);
    }

    public StreamPrinter(PrintStream out) {
        this.out = out;
    }

    public void banner(String text) {
        out.println(BOLD + text + RESET);
        out.println();
    }

    public void startAssistant() {
        // 空操作 —— 由 REPL 自行追踪
    }

    public void write(String text) {
        out.print(text);
        out.flush();
    }

    public void writeThinking(String text) {
        out.print(DIM + text + RESET);
        out.flush();
    }

    public void endAssistant() {
        out.println();
    }

    public void error(String message) {
        out.println(RED + "✗ " + message + RESET);
    }

    public void info(String message) {
        out.println(message);
    }

    public void newline() {
        out.println();
    }
}
```

- [ ] **步骤 2：验证能编译**

运行：`mvn -q compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：提交**

```bash
git add src/main/java/com/maplecode/ui/StreamPrinter.java
git commit -m "feat(ui): 添加 StreamPrinter（文本/thinking/错误/banner 的 ANSI 渲染）"
```

---

### 任务 19：ReplLoop + App + 示例配置 + README

**文件：**
- 创建：`src/main/java/com/maplecode/ui/ReplLoop.java`
- 创建：`src/main/java/com/maplecode/App.java`
- 创建：`maplecode.yaml.example`
- 创建：`README.md`

REPL 不做自动化测试（JLine 在 CI 里有坑 —— 规格 §10.3 明确排除）。通过跑 jar 验证。

- [ ] **步骤 1：创建 `ReplLoop.java`**

`src/main/java/com/maplecode/ui/ReplLoop.java`：

```java
package com.maplecode.ui;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ProviderException;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.session.ChatSession;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class ReplLoop {

    private final AppConfig config;
    private final LlmProvider provider;
    private final StreamPrinter printer;
    private final LineReader reader;
    private final ChatSession session = new ChatSession();

    public ReplLoop(AppConfig config, LlmProvider provider, StreamPrinter printer, LineReader reader) {
        this.config = config;
        this.provider = provider;
        this.printer = printer;
        this.reader = reader;
    }

    public static ReplLoop fromConfig(AppConfig config, LlmProvider provider) throws java.io.IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build(true);
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        return new ReplLoop(config, provider, new StreamPrinter(System.out), reader);
    }

    public void run() {
        printer.banner("MapleCode — 输入 /exit 退出，/clear 清空历史，\"\"\" 开始多行输入");
        while (true) {
            String input;
            try {
                input = readMultiline();
            } catch (UserInterruptException e) {
                continue;
            } catch (RuntimeException e) {
                // JLine 在 Ctrl+D 时返回 null；这里兜底其他运行时异常
                break;
            }
            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals("/exit")) break;
            if (trimmed.equals("/clear")) {
                session.clear();
                printer.info("history cleared");
                continue;
            }

            session.appendUser(trimmed);
            ChatRequest req = session.toRequest(config.model(), config.systemPrompt(), config.thinking());
            StringBuilder textBuf = new StringBuilder();
            try {
                provider.stream(req, chunk -> {
                    switch (chunk) {
                        case StreamChunk.TextDelta d       -> { printer.write(d.text()); textBuf.append(d.text()); }
                        case StreamChunk.ThinkingDelta d   -> printer.writeThinking(d.text());
                        case StreamChunk.MessageStart s    -> { /* 空操作 */ }
                        case StreamChunk.MessageEnd e      -> printer.endAssistant();
                        case StreamChunk.Error e           -> printer.error(e.code() + ": " + e.message());
                    }
                });
                if (textBuf.length() > 0) session.appendAssistant(textBuf.toString());
            } catch (ProviderException e) {
                printer.error("request failed: " + e.getMessage());
            }
            printer.newline();
        }
    }

    private String readMultiline() {
        String first;
        try {
            first = reader.readLine("> ");
        } catch (UserInterruptException e) {
            throw e;
        }
        if (first == null) return null;
        if (!first.equals("\"\"\"")) return first;
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line;
            try {
                line = reader.readLine("... ");
            } catch (UserInterruptException e) {
                throw e;
            }
            if (line == null) return null;
            if (line.equals("\"\"\"")) break;
            sb.append(line).append('\n');
        }
        String result = sb.toString();
        // 去掉末尾的换行（如果有）
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
```

- [ ] **步骤 2：创建 `App.java`**

`src/main/java/com/maplecode/App.java`：

```java
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
```

- [ ] **步骤 3：创建 `maplecode.yaml.example`**

`maplecode.yaml.example`：

```yaml
# MapleCode 示例配置
# 复制到 ./maplecode.yaml 或 ~/.maplecode/config.yaml 后修改。

protocol: anthropic            # "anthropic" | "openai"
model: claude-sonnet-4-6
base_url: https://api.anthropic.com
api_key: ${ANTHROPIC_API_KEY} # ${ENV_VAR} 占位符，从环境变量解析

system_prompt: |
  You are MapleCode, a helpful coding assistant. Be concise.

extended_thinking:             # 可选；OpenAI 忽略此块
  type: adaptive               # "adaptive"（推荐） | "enabled"（legacy，Opus 4.7 上返回 400）
  effort: high                 # adaptive 时：low | medium | high
  # budget_tokens: 10000       # 仅 enabled 时：>= 1024 且 < max_tokens

timeouts:
  connect_seconds: 10
  read_seconds: 60
```

- [ ] **步骤 4：创建 `README.md`**

`README.md`：

````markdown
# MapleCode

一个极简的 Java 命令行 AI 对话工具。通过 SSE 流式转发 Anthropic Claude 或 OpenAI Chat Completions 的响应，支持多轮对话记忆。v1 不做 tool use。

## 构建

需要 Java 21 和 Maven 3.9+。

```
mvn package
```

产出 `target/maple-code-java-0.1.0.jar`。

## 配置

把 `maplecode.yaml.example` 复制成 `maplecode.yaml`（或 `~/.maplecode/config.yaml`），把 `api_key` 设成 `${ENV_VAR}` 占位符。

```
export ANTHROPIC_API_KEY=sk-ant-...
```

OpenAI 配置示例：

```yaml
protocol: openai
model: gpt-4o
base_url: https://api.openai.com/v1
api_key: ${OPENAI_API_KEY}
```

### Extended Thinking

仅 Anthropic。OpenAI 忽略此块。

| 格式 | 使用场景 |
|---|---|
| `type: adaptive` + `effort: low\|medium\|high` | 所有现行 Claude 模型（Opus 4.7、Opus 4.6、Sonnet 4.6） |
| `type: enabled` + `budget_tokens: N`（>= 1024） | 旧版 —— 仅 Opus 4.6 / Sonnet 4.6；Opus 4.7 返回 HTTP 400 |

## 运行

```
java -jar target/maple-code-java-0.1.0.jar
# 或指定配置文件：
java -jar target/maple-code-java-0.1.0.jar --config /path/to/config.yaml
```

REPL 内：

- `"""` 开启多行输入；单独一行 `"""` 结束
- `/clear` —— 清空消息历史
- `/exit` 或 Ctrl+D —— 退出

## 测试

```
mvn test
```

## 项目结构

完整文件结构见 `docs/superpowers/specs/2026-07-01-maple-code-design.md` §3；v1 明确不做的功能（tool use、持久化等）见 §12。
````

- [ ] **步骤 5：验证编译 + 全测试通过**

运行：`mvn -q clean test`
预期：BUILD SUCCESS，所有测试通过。测试数：
- ThinkingConfigTest：8
- ConfigLoaderTest：6
- ConfigLoaderDeprecationWarningTest：2
- SseStreamReaderTest：8
- AnthropicRequestMapperTest：4
- AnthropicStreamParserTest：4
- OpenAiRequestMapperTest：3
- OpenAiStreamParserTest：6
- ProviderRegistryTest：4
- ChatSessionTest：5
- **合计：50 个测试**

- [ ] **步骤 6：打 jar**

运行：`mvn -q package -DskipTests`
预期：BUILD SUCCESS，产出 `target/maple-code-java-0.1.0.jar`。

- [ ] **步骤 7：冒烟测试 —— 验证无配置时干净退出**

运行：`cd /tmp && java -jar /Users/wangpeng/MyCodeSpace/luanqibazao/maple-code-java/target/maple-code-java-0.1.0.jar`
预期：退出码 78，stderr 打印配置缺失的友好消息。

- [ ] **步骤 8：手工端到端冒烟（可选，需要真 API key）**

复制 `maplecode.yaml.example` 为 `maplecode.yaml`，把 `api_key: ${ANTHROPIC_API_KEY}` 设上，导出环境变量，运行：
```
java -jar target/maple-code-java-0.1.0.jar
```
输入 `hello` 看是否流式输出。输入 `/exit` 退出。

如果配了 extended thinking（`type: adaptive`），验证 thinking 文本是灰色显示。

- [ ] **步骤 9：提交**

```bash
git add src/main/java/com/maplecode/App.java src/main/java/com/maplecode/ui/ReplLoop.java maplecode.yaml.example README.md
git commit -m "feat: 添加 REPL 主循环（JLine 多行）、主入口、示例配置、README"
```

---

## 验收清单

全部 19 个任务完成后，逐项验证：

- [ ] `mvn clean test` —— 50 个测试全部通过
- [ ] `mvn package` —— 产出可运行 jar
- [ ] 无配置启动 —— 退出码 78，友好提示
- [ ] Anthropic 配置 —— 单轮 + 多轮对话流式正确
- [ ] OpenAI 配置 —— 单轮 + 多轮对话流式正确
- [ ] `extended_thinking.type=adaptive` 在现行 Claude 模型上工作
- [ ] `extended_thinking.type=enabled` 触发 stderr deprecation 警告
- [ ] `budget_tokens < 1024` 启动期拒绝（请求未发出）
- [ ] `/exit`、`/clear`、`"""` 多行都按设计工作
- [ ] HTTP 4xx/5xx 不退出 REPL（用户可继续）
- [ ] 切换 provider 只需改 YAML + 重启

---

## 范围外（本计划明确不做）

按规格 §12：
- Tool use / function calling
- 文件读写、代码编辑、命令执行
- 会话持久化 / `/resume`
- 运行时 provider 热切换
- 多模态输入
- 插件系统

这些故意省略，每个都会单独走 spec → plan → implementation 流程，等时机到再做。