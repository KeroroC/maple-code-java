# MapleCode Esc Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `/cancel`, cancel an active Agent stream with one Esc, and clear normal or multiline input with two Esc presses within 500ms.

**Architecture:** Add a focused `EscapeController` that owns JLine Esc bindings and temporarily reads the terminal in raw mode only while the provider is streaming. Keep the synchronous REPL architecture; cancellation propagates from a volatile Agent flag through a cancellation-aware stream sink, while `ReplLoop` switches the controller between input, streaming, and inactive states using existing `AgentEvent`s.

**Tech Stack:** Java 21, JLine 3.27.0, JUnit 5, Mockito 5.20.0, Maven Surefire 3.5.2

---

## File map

- Create `src/main/java/com/maplecode/ui/EscapeController.java`: JLine bindings, multiline abort state, raw terminal listener, terminal restoration.
- Create `src/test/java/com/maplecode/ui/EscapeControllerTest.java`: controller unit tests.
- Create `src/test/java/com/maplecode/ui/ReplLoopEscapeTest.java`: REPL/controller integration tests.
- Create `src/test/java/com/maplecode/AppCommandRegistryTest.java`: production command assembly test.
- Modify `AgentLoop`, `SseStreamReader`, `ReplLoop`, `App`, and their tests.
- Remove `CancelCommand`, its test, and obsolete `CommandContext` cancellation methods.
- Update active project and design documentation.

---

### Task 1: Make Agent cancellation immediate and per-run

**Files:**
- Modify: `src/test/java/com/maplecode/agent/AgentLoopTest.java`
- Modify: `src/main/java/com/maplecode/agent/AgentLoop.java:62-88,158-203`

- [ ] **Step 1: Write failing stream-cancellation tests**

Remove `cancelBeforeRunEmitsUserCancelled()` and add:

```java
@Test
void cancelDuringStreamStopsLaterTextAndResetsForNextRun() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var agentRef = new java.util.concurrent.atomic.AtomicReference<AgentLoop>();
    LlmProvider provider = (req, sink) -> {
        if (calls.incrementAndGet() == 1) {
            sink.accept(new StreamChunk.TextDelta("before"));
            agentRef.get().cancel();
            sink.accept(new StreamChunk.TextDelta("after"));
            sink.accept(new StreamChunk.MessageEnd(StopReason.END_TURN, null));
        } else {
            sink.accept(new StreamChunk.TextDelta("next"));
            sink.accept(new StreamChunk.MessageEnd(StopReason.END_TURN, null));
        }
    };
    var registry = new ToolRegistry(List.of());
    var session = new ChatSession();
    var agent = new AgentLoop(provider, registry, new ToolExecutor(registry),
        session, AgentConfig.defaults(), null);
    agentRef.set(agent);

    var first = new ArrayList<AgentEvent>();
    agent.run("first", first::add);
    assertEquals(StopReason.USER_CANCELLED, first.stream()
        .filter(AgentEvent.AgentStop.class::isInstance)
        .map(AgentEvent.AgentStop.class::cast)
        .findFirst().orElseThrow().reason());
    assertTrue(first.stream()
        .filter(AgentEvent.TextDelta.class::isInstance)
        .map(AgentEvent.TextDelta.class::cast)
        .noneMatch(e -> e.text().equals("after")));
    assertEquals(1, session.size());

    var second = new ArrayList<AgentEvent>();
    agent.run("second", second::add);
    assertEquals(2, calls.get());
    assertTrue(second.stream()
        .filter(AgentEvent.TextDelta.class::isInstance)
        .map(AgentEvent.TextDelta.class::cast)
        .anyMatch(e -> e.text().equals("next")));
}

@Test
void cancelAfterToolResponsePreventsToolExecution() {
    var agentRef = new java.util.concurrent.atomic.AtomicReference<AgentLoop>();
    LlmProvider provider = (req, sink) -> {
        sink.accept(new StreamChunk.ToolUseStart("t1", "read_file"));
        sink.accept(new StreamChunk.ToolUseEnd("t1", "read_file",
            new ObjectMapper().createObjectNode()));
        sink.accept(new StreamChunk.MessageEnd(StopReason.TOOL_USE, null));
        agentRef.get().cancel();
    };
    var tool = new RecordingTool("read_file", ToolResult.ok("content"));
    var registry = new ToolRegistry(List.of(tool));
    var agent = new AgentLoop(provider, registry, new ToolExecutor(registry),
        new ChatSession(), AgentConfig.defaults(), null);
    agentRef.set(agent);

    var events = new ArrayList<AgentEvent>();
    agent.run("read", events::add);

    assertEquals(0, tool.calls().size());
    assertEquals(StopReason.USER_CANCELLED, events.stream()
        .filter(AgentEvent.AgentStop.class::isInstance)
        .map(AgentEvent.AgentStop.class::cast)
        .findFirst().orElseThrow().reason());
}
```

- [ ] **Step 2: Run the tests and verify RED**

```bash
mvn test -Dtest='AgentLoopTest#cancelDuringStreamStopsLaterTextAndResetsForNextRun+cancelAfterToolResponsePreventsToolExecution' -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: FAIL because cancellation is not checked at chunk boundaries, the flag remains set for the second run, and the late-cancelled tool executes.

- [ ] **Step 3: Implement the minimal cancellation lifecycle**

Add:

```java
import java.util.concurrent.CancellationException;
```

Replace `cancel()` and `run()` with:

```java
public void cancel() {
    if (running) cancelled = true;
}

public void run(String userInput, Consumer<AgentEvent> sink) {
    cancelled = false;
    running = true;
    try {
        runInternal(userInput, sink);
    } finally {
        running = false;
    }
}
```

Replace the provider call with:

```java
try {
    provider.stream(req, chunk -> {
        if (cancelled) throw new CancellationException("agent cancelled");
        col.accept(chunk);
    });
} catch (CancellationException e) {
    sink.accept(new AgentEvent.AgentStop(
        StopReason.USER_CANCELLED, "user cancelled"));
    return;
} catch (ProviderException e) {
    sink.accept(new AgentEvent.AgentStop(
        StopReason.PROVIDER_ERROR, e.getMessage()));
    return;
}
if (cancelled) {
    sink.accept(new AgentEvent.AgentStop(
        StopReason.USER_CANCELLED, "user cancelled"));
    return;
}
```

- [ ] **Step 4: Run focused and package tests**

```bash
mvn test -Dtest='AgentLoopTest,ResponseCollectorTest,BatchTest' -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/agent/AgentLoop.java src/test/java/com/maplecode/agent/AgentLoopTest.java
git commit -m "fix(agent): make cancellation immediate and per-run"
```

---

### Task 2: Preserve cancellation through the SSE layer

**Files:**
- Modify: `src/test/java/com/maplecode/http/SseStreamReaderTest.java`
- Modify: `src/main/java/com/maplecode/http/SseStreamReader.java:20-58`

- [ ] **Step 1: Add failing exception-propagation tests**

Add imports and tests:

```java
import com.maplecode.error.ProviderException;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Test
void cancellationFromEventSinkPropagatesUnwrapped() {
    HttpResponse<Stream<String>> resp = mock(HttpResponse.class);
    when(resp.body()).thenReturn(Stream.of("data: one", ""));
    var expected = new CancellationException("cancelled");
    var actual = assertThrows(CancellationException.class,
        () -> new SseStreamReader().read(resp, event -> { throw expected; }));
    assertSame(expected, actual);
}

@Test
void otherRuntimeFailureIsStillWrapped() {
    HttpResponse<Stream<String>> resp = mock(HttpResponse.class);
    when(resp.body()).thenReturn(Stream.of("data: one", ""));
    assertThrows(ProviderException.class,
        () -> new SseStreamReader().read(resp,
            event -> { throw new IllegalStateException("boom"); }));
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn test -Dtest='SseStreamReaderTest#cancellationFromEventSinkPropagatesUnwrapped+otherRuntimeFailureIsStillWrapped' -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: the cancellation test fails because the exception is wrapped.

- [ ] **Step 3: Implement the dedicated cancellation catch**

```java
import java.util.concurrent.CancellationException;

// In read():
} catch (CancellationException e) {
    throw e;
} catch (RuntimeException e) {
    throw new ProviderException("SSE stream read failed", e);
}
```

- [ ] **Step 4: Run all SSE tests**

```bash
mvn test -Dtest=SseStreamReaderTest -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/http/SseStreamReader.java src/test/java/com/maplecode/http/SseStreamReaderTest.java
git commit -m "fix(http): preserve stream cancellation"
```

---

### Task 3: Add JLine double-Esc input clearing

**Files:**
- Create: `src/main/java/com/maplecode/ui/EscapeController.java`
- Create: `src/test/java/com/maplecode/ui/EscapeControllerTest.java`

- [ ] **Step 1: Create failing binding and buffer tests**

Create `EscapeControllerTest.java`:

```java
package com.maplecode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EscapeControllerTest {
    private LineReader reader;
    private Buffer buffer;
    private Terminal terminal;
    private KeyMap<Binding> main;
    private Map<String, Widget> widgets;

    @BeforeEach
    void setUp() {
        reader = mock(LineReader.class);
        buffer = mock(Buffer.class);
        terminal = mock(Terminal.class);
        main = new KeyMap<>();
        widgets = new HashMap<>();
        when(reader.getBuffer()).thenReturn(buffer);
        when(reader.getTerminal()).thenReturn(terminal);
        when(reader.getWidgets()).thenReturn(widgets);
        when(reader.getKeyMaps()).thenReturn(Map.of(LineReader.MAIN, main));
    }

    @Test
    void installBindsSingleAndDoubleEscAt500ms() {
        var controller = new EscapeController(reader);
        controller.installInputBindings();
        assertEquals(500L, main.getAmbiguousTimeout());
        assertEquals(new Reference(EscapeController.NOOP_WIDGET),
            main.getBound(KeyMap.esc()));
        assertEquals(new Reference(EscapeController.CLEAR_WIDGET),
            main.getBound(KeyMap.esc() + KeyMap.esc()));
    }

    @Test
    void installKeepsLongerArrowBinding() {
        var up = new Reference(LineReader.UP_HISTORY);
        main.bind(up, KeyMap.esc() + "[A");
        new EscapeController(reader).installInputBindings();
        assertEquals(up, main.getBound(KeyMap.esc() + "[A"));
    }

    @Test
    void clearWidgetClearsNormalBufferWithoutAcceptingLine() {
        var controller = new EscapeController(reader);
        controller.installInputBindings();
        assertTrue(widgets.get(EscapeController.CLEAR_WIDGET).apply());
        verify(buffer).clear();
        verify(reader, never()).callWidget(LineReader.ACCEPT_LINE);
        assertFalse(controller.consumeMultilineAbort());
    }

    @Test
    void clearWidgetAbortsWholeMultilineInput() {
        var controller = new EscapeController(reader);
        controller.installInputBindings();
        controller.beginMultiline();
        assertTrue(widgets.get(EscapeController.CLEAR_WIDGET).apply());
        verify(buffer).clear();
        verify(reader).callWidget(LineReader.ACCEPT_LINE);
        assertTrue(controller.consumeMultilineAbort());
        assertFalse(controller.consumeMultilineAbort());
        controller.endMultiline();
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn test -Dtest=EscapeControllerTest -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: test compilation fails because `EscapeController` does not exist.

- [ ] **Step 3: Create the input-control implementation**

Create `EscapeController.java`:

```java
package com.maplecode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EscapeController {
    public static final long DOUBLE_ESC_TIMEOUT_MS = 500L;
    static final String NOOP_WIDGET = "maple-escape-noop";
    static final String CLEAR_WIDGET = "maple-clear-input";

    private final LineReader reader;
    private final AtomicBoolean multiline = new AtomicBoolean();
    private final AtomicBoolean multilineAbort = new AtomicBoolean();

    public EscapeController(LineReader reader) {
        this.reader = reader;
    }

    public void installInputBindings() {
        reader.getWidgets().put(NOOP_WIDGET, () -> true);
        reader.getWidgets().put(CLEAR_WIDGET, this::clearInput);
        Set<KeyMap<Binding>> configured =
            Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS)) {
            KeyMap<Binding> map = reader.getKeyMaps().get(name);
            if (map == null || !configured.add(map)) continue;
            map.setAmbiguousTimeout(DOUBLE_ESC_TIMEOUT_MS);
            map.bind(new Reference(NOOP_WIDGET), KeyMap.esc());
            map.bind(new Reference(CLEAR_WIDGET), KeyMap.esc() + KeyMap.esc());
        }
    }

    private boolean clearInput() {
        reader.getBuffer().clear();
        if (multiline.get()) {
            multilineAbort.set(true);
            reader.callWidget(LineReader.ACCEPT_LINE);
        }
        return true;
    }

    public void beginMultiline() {
        multilineAbort.set(false);
        multiline.set(true);
    }

    public void endMultiline() {
        multiline.set(false);
    }

    public boolean consumeMultilineAbort() {
        return multilineAbort.getAndSet(false);
    }

}
```

- [ ] **Step 4: Run and verify GREEN**

```bash
mvn test -Dtest=EscapeControllerTest -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: all four tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/ui/EscapeController.java src/test/java/com/maplecode/ui/EscapeControllerTest.java
git commit -m "feat(ui): add double escape input clearing"
```

---

### Task 4: Add the raw-mode single-Esc listener

**Files:**
- Modify: `src/test/java/com/maplecode/ui/EscapeControllerTest.java`
- Modify: `src/main/java/com/maplecode/ui/EscapeController.java`

- [ ] **Step 1: Add failing listener tests**

Add imports, fields, and setup:

```java
import org.jline.terminal.Attributes;
import org.jline.utils.NonBlockingReader;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

private NonBlockingReader terminalReader;
private Attributes originalAttributes;

// In setUp():
terminalReader = mock(NonBlockingReader.class);
originalAttributes = mock(Attributes.class);
when(terminal.reader()).thenReturn(terminalReader);
when(terminal.enterRawMode()).thenReturn(originalAttributes);
```

Add tests:

```java
@Test
void singleEscCancelsOnceAndRestoresTerminal() throws Exception {
    when(terminalReader.read(100L)).thenReturn(27);
    var latch = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var controller = new EscapeController(reader);
    controller.startAgentStreaming(() -> {
        calls.incrementAndGet();
        latch.countDown();
    });
    assertTrue(latch.await(1, TimeUnit.SECONDS));
    controller.stopAgentStreaming();
    assertEquals(1, calls.get());
    verify(terminal).setAttributes(originalAttributes);
}

@Test
void nonEscInputDoesNotCancel() throws Exception {
    when(terminalReader.read(100L))
        .thenReturn((int) 'x')
        .thenReturn(NonBlockingReader.EOF);
    var calls = new AtomicInteger();
    var controller = new EscapeController(reader);
    controller.startAgentStreaming(calls::incrementAndGet);
    verify(terminal, timeout(1000)).setAttributes(originalAttributes);
    controller.stopAgentStreaming();
    assertEquals(0, calls.get());
}

@Test
void repeatedStopRestoresTerminalOnlyOnce() {
    var controller = new EscapeController(reader);
    controller.startAgentStreaming(() -> {});
    controller.stopAgentStreaming();
    controller.stopAgentStreaming();
    verify(terminal, times(1)).setAttributes(originalAttributes);
}

@Test
void listenerFailureRestoresTerminalWithoutCancelling() throws Exception {
    when(terminalReader.read(100L)).thenThrow(new IOException("boom"));
    var calls = new AtomicInteger();
    var controller = new EscapeController(reader);
    controller.startAgentStreaming(calls::incrementAndGet);
    verify(terminal, timeout(1000)).setAttributes(originalAttributes);
    assertEquals(0, calls.get());
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn test -Dtest='EscapeControllerTest#singleEscCancelsOnceAndRestoresTerminal+nonEscInputDoesNotCancel+repeatedStopRestoresTerminalOnlyOnce+listenerFailureRestoresTerminalWithoutCancelling' -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: test compilation fails because the streaming lifecycle methods do not exist yet.

- [ ] **Step 3: Implement the complete listener**

Add fields/imports:

```java
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import java.io.IOException;

// Change the declaration and replace the complete field/constructor block with:
public final class EscapeController implements AutoCloseable {
    private final LineReader reader;
    private final Terminal terminal;
    private final AtomicBoolean multiline = new AtomicBoolean();
    private final AtomicBoolean multilineAbort = new AtomicBoolean();
    private final AtomicBoolean streaming = new AtomicBoolean();
    private Thread listenerThread;
    private Attributes originalAttributes;

    public EscapeController(LineReader reader) {
        this.reader = reader;
        this.terminal = reader.getTerminal();
    }
```

Replace the streaming methods with:

```java
public synchronized void startAgentStreaming(Runnable cancelAction) {
    if (streaming.get()) return;
    try {
        originalAttributes = terminal.enterRawMode();
        streaming.set(true);
        listenerThread = new Thread(
            () -> listenForEscape(cancelAction), "maple-escape-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    } catch (RuntimeException e) {
        streaming.set(false);
        restoreTerminalLocked();
        System.err.println("[input] WARN: cannot start Esc listener: " + e.getMessage());
    }
}

private void listenForEscape(Runnable cancelAction) {
    try {
        while (streaming.get()) {
            int ch = terminal.reader().read(100L);
            if (ch == NonBlockingReader.READ_EXPIRED) continue;
            if (ch == NonBlockingReader.EOF) break;
            if (ch == 27) {
                cancelAction.run();
                break;
            }
        }
    } catch (IOException e) {
        if (streaming.get()) {
            System.err.println("[input] WARN: Esc listener failed: " + e.getMessage());
        }
    } finally {
        finishListener(Thread.currentThread());
    }
}

private synchronized void finishListener(Thread current) {
    if (listenerThread != current) return;
    streaming.set(false);
    listenerThread = null;
    restoreTerminalLocked();
}

public void stopAgentStreaming() {
    Thread toJoin;
    synchronized (this) {
        streaming.set(false);
        toJoin = listenerThread;
        listenerThread = null;
        if (toJoin != null && toJoin != Thread.currentThread()) toJoin.interrupt();
        restoreTerminalLocked();
    }
    if (toJoin != null && toJoin != Thread.currentThread()) {
        try {
            toJoin.join(250L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

private void restoreTerminalLocked() {
    if (originalAttributes == null) return;
    try {
        terminal.setAttributes(originalAttributes);
    } finally {
        originalAttributes = null;
    }
}

@Override
public void close() {
    stopAgentStreaming();
}
```

- [ ] **Step 4: Run and verify GREEN**

```bash
mvn test -Dtest=EscapeControllerTest -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: all controller tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maplecode/ui/EscapeController.java src/test/java/com/maplecode/ui/EscapeControllerTest.java
git commit -m "feat(ui): cancel agent stream with escape"
```

---

### Task 5: Integrate Esc states into `ReplLoop`

**Files:**
- Create: `src/test/java/com/maplecode/ui/ReplLoopEscapeTest.java`
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java:27-79,108-217,254-279`
- Modify: `src/main/java/com/maplecode/App.java:209-231`

- [ ] **Step 1: Add failing REPL integration tests**

Create `ReplLoopEscapeTest.java`:

```java
package com.maplecode.ui;

import com.maplecode.agent.AgentConfig;
import com.maplecode.command.CommandRegistry;
import com.maplecode.config.AppConfig;
import com.maplecode.memory.MemoryManager;
import com.maplecode.permission.PermissionEngine;
import com.maplecode.permission.PermissionMode;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import com.maplecode.tool.ToolExecutor;
import com.maplecode.tool.ToolRegistry;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReplLoopEscapeTest {
    private static ReplLoop newRepl(LineReader reader, LlmProvider provider,
                                    MemoryManager memory, EscapeController escape) {
        var config = mock(AppConfig.class);
        when(config.model()).thenReturn("model");
        var engine = mock(PermissionEngine.class);
        when(engine.mode()).thenReturn(PermissionMode.DEFAULT);
        var registry = new ToolRegistry(List.of());
        return new ReplLoop(config, provider, mock(StreamPrinter.class), reader,
            registry, new ToolExecutor(registry), engine, AgentConfig.defaults(),
            null, null, memory, null, new CommandRegistry(), escape);
    }

    @Test
    void cancelledAgentStopsListenerAndSkipsMemoryExtraction() {
        var reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("hello", (String) null);
        var memory = mock(MemoryManager.class);
        var escape = mock(EscapeController.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(escape).startAgentStreaming(any(Runnable.class));
        LlmProvider provider = (req, sink) ->
            sink.accept(new StreamChunk.TextDelta("ignored"));

        newRepl(reader, provider, memory, escape).run();

        verify(escape).startAgentStreaming(any(Runnable.class));
        verify(escape, atLeastOnce()).stopAgentStreaming();
        verify(memory, never()).extractAsync(any());
    }

    @Test
    void multilineAbortDiscardsAccumulatedLines() {
        var reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("\"\"\"");
        when(reader.readLine("... ")).thenReturn("first", "");
        var escape = mock(EscapeController.class);
        when(escape.consumeMultilineAbort()).thenReturn(false, true);
        var repl = newRepl(reader, (req, sink) -> {}, null, escape);

        assertEquals("", repl.readMultiline());
        verify(escape).beginMultiline();
        verify(escape).endMultiline();
    }

    @Test
    void toolBatchStopsListenerBeforeNextIteration() {
        var reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("tools", (String) null);
        var escape = mock(EscapeController.class);
        var calls = new AtomicInteger();
        LlmProvider provider = (req, sink) -> {
            if (calls.incrementAndGet() == 1) {
                sink.accept(new StreamChunk.ToolUseStart("t1", "unknown"));
                sink.accept(new StreamChunk.ToolUseEnd("t1", "unknown",
                    new ObjectMapper().createObjectNode()));
                sink.accept(new StreamChunk.MessageEnd(
                    StreamChunk.StopReason.TOOL_USE, null));
            } else {
                sink.accept(new StreamChunk.TextDelta("done"));
                sink.accept(new StreamChunk.MessageEnd(
                    StreamChunk.StopReason.END_TURN, null));
            }
        };

        newRepl(reader, provider, null, escape).run();

        var order = inOrder(escape);
        order.verify(escape).startAgentStreaming(any(Runnable.class));
        order.verify(escape).stopAgentStreaming();
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn test -Dtest=ReplLoopEscapeTest -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: compilation fails because the constructor has no `EscapeController` parameter and `readMultiline()` is private.

- [ ] **Step 3: Wire the controller into constructors**

Add the field and main-constructor parameter:

```java
private final EscapeController escapeController;

// Final main-constructor parameter:
EscapeController escapeController

// Constructor assignment:
this.escapeController = escapeController;
```

The compatibility constructor passes `null` as the final argument.

- [ ] **Step 4: Centralize Agent execution and event-driven state switching**

Add imports:

```java
import com.maplecode.provider.StreamChunk.StopReason;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
```

Change `CommandContextImpl.sendToAgent()` to call `runAgent(prompt)`. Add:

```java
private StopReason runAgent(String prompt) {
    AtomicReference<StopReason> finalStop = new AtomicReference<>();
    Consumer<AgentEvent> sink = event -> {
        if (escapeController != null) {
            if (event instanceof AgentEvent.IterationStart) {
                escapeController.startAgentStreaming(agent::cancel);
            } else if (event instanceof AgentEvent.BatchStart
                || event instanceof AgentEvent.AgentStop) {
                escapeController.stopAgentStreaming();
            }
        }
        if (event instanceof AgentEvent.AgentStop stop) {
            finalStop.set(stop.reason());
        }
        printer.accept(event);
    };
    try {
        agent.run(prompt, sink);
    } finally {
        if (escapeController != null) escapeController.stopAgentStreaming();
    }
    return finalStop.get();
}
```

Replace normal conversation execution with:

```java
StopReason stopReason = runAgent(trimmed);
if (memoryManager != null && stopReason != StopReason.USER_CANCELLED) {
    memoryManager.extractAsync(agent.session().recentMessages(20));
}
```

- [ ] **Step 5: Make multiline cancellation discard the whole block**

Make `readMultiline()` package-private and replace it with:

```java
String readMultiline() {
    String first = reader.readLine("> ");
    if (first == null) return null;
    if (!first.equals("\"\"\"")) return first;
    if (escapeController != null) escapeController.beginMultiline();
    try {
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line = reader.readLine("... ");
            if (escapeController != null
                && escapeController.consumeMultilineAbort()) return "";
            if (line == null) return null;
            if (line.equals("\"\"\"")) break;
            sb.append(line).append('\n');
        }
        if (!sb.isEmpty()) sb.setLength(sb.length() - 1);
        return sb.toString();
    } finally {
        if (escapeController != null) escapeController.endMultiline();
    }
}
```

- [ ] **Step 6: Install the controller in `App`**

After creating `reader`:

```java
EscapeController escapeController = new EscapeController(reader);
escapeController.installInputBindings();
```

Pass it as the final `ReplLoop` constructor argument and import `com.maplecode.ui.EscapeController`.

- [ ] **Step 7: Run focused integration tests**

```bash
mvn test -Dtest='ReplLoopEscapeTest,AgentLoopTest,EscapeControllerTest' -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: all selected tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/maplecode/ui/ReplLoop.java src/main/java/com/maplecode/App.java src/test/java/com/maplecode/ui/ReplLoopEscapeTest.java
git commit -m "feat(ui): integrate escape control states"
```

---

### Task 6: Remove `/cancel` from production command assembly

**Files:**
- Create: `src/test/java/com/maplecode/AppCommandRegistryTest.java`
- Modify: `src/main/java/com/maplecode/App.java:190-207`
- Modify: `src/main/java/com/maplecode/command/CommandContext.java:21-31`
- Modify: `src/main/java/com/maplecode/ui/ReplLoop.java:120-128`
- Delete: `src/main/java/com/maplecode/command/CancelCommand.java`
- Delete: `src/test/java/com/maplecode/command/CancelCommandTest.java`

- [ ] **Step 1: Add a failing production-registry test**

Create `AppCommandRegistryTest.java`:

```java
package com.maplecode;

import com.maplecode.command.CommandRegistry;
import com.maplecode.compact.CompactCoordinator;
import com.maplecode.memory.MemoryManager;
import com.maplecode.session.archive.SessionArchive;
import com.maplecode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AppCommandRegistryTest {
    @Test
    void productionRegistryDoesNotExposeCancel() {
        CommandRegistry registry = App.createCommandRegistry(
            new ToolRegistry(List.of()), mock(SessionArchive.class),
            mock(CompactCoordinator.class), mock(MemoryManager.class));
        assertTrue(registry.lookup("help").isPresent());
        assertTrue(registry.lookup("exit").isPresent());
        assertTrue(registry.lookup("cancel").isEmpty());
        assertFalse(registry.completableNames().contains("cancel"));
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
mvn test -Dtest=AppCommandRegistryTest -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: compilation fails because `App.createCommandRegistry(...)` does not exist.

- [ ] **Step 3: Extract production command assembly without `/cancel`**

Add to `App`:

```java
static CommandRegistry createCommandRegistry(
        ToolRegistry tools, SessionArchive archive,
        CompactCoordinator coord, MemoryManager memoryManager) {
    CommandRegistry commands = new CommandRegistry();
    commands.register(new ClearCommand(coord));
    commands.register(new CompactCommand(coord));
    commands.register(new DoCommand());
    commands.register(new ExitCommand());
    commands.register(new HelpCommand(commands));
    if (memoryManager != null) commands.register(new MemoryCommand(memoryManager));
    commands.register(new ModeCommand());
    commands.register(new NewCommand(archive, coord));
    commands.register(new PlanCommand());
    commands.register(new ResumeCommand(archive));
    commands.register(new ReviewCommand());
    commands.register(new StatusCommand());
    commands.register(new ToolsCommand(tools));
    return commands;
}
```

Replace inline registration with:

```java
CommandRegistry cmdRegistry = createCommandRegistry(
    registry, sessionArchive, coord, memoryManager);
```

- [ ] **Step 4: Remove obsolete command APIs and files**

Delete `CancelCommand.java` and `CancelCommandTest.java`. Remove these declarations and implementations:

```java
void cancelCurrentAgentRun();
boolean isAgentRunning();
```

- [ ] **Step 5: Run command tests and verify GREEN**

```bash
mvn test -Dtest='AppCommandRegistryTest,*CommandTest,CommandRegistryTest,CommandCompleterTest' -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: all selected tests pass.

- [ ] **Step 6: Verify no source reference remains**

```bash
rg -n 'CancelCommand|cancelCurrentAgentRun|/cancel' src/main/java src/test/java
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maplecode/App.java src/main/java/com/maplecode/command/CommandContext.java src/main/java/com/maplecode/ui/ReplLoop.java src/test/java/com/maplecode/AppCommandRegistryTest.java
git add -u src/main/java/com/maplecode/command/CancelCommand.java src/test/java/com/maplecode/command/CancelCommandTest.java
git commit -m "refactor(command): replace cancel command with escape"
```

---

### Task 7: Update documentation and run full verification

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-07-09-maple-code-command-framework-design.md`
- Modify: `docs/superpowers/specs/2026-07-09-maple-code-tui-status-bar-design.md`

- [ ] **Step 1: Update active behavior documentation**

Apply these exact semantic changes:

```text
Remove /cancel from built-in command counts, tables, registration examples,
help/completion examples, and behavior sections.
Document one Esc during Agent streaming as immediate cancellation.
Document two Esc presses within 500ms as input clearing.
Document double Esc in multiline mode as discarding the whole block.
Keep unrelated Ctrl-C and Ctrl-D behavior unchanged.
```

- [ ] **Step 2: Check for stale active references**

```bash
rg -n '/cancel|CancelCommand|14 个内置命令|14 commands' CLAUDE.md docs/superpowers/specs/2026-07-09-maple-code-command-framework-design.md docs/superpowers/specs/2026-07-09-maple-code-tui-status-bar-design.md
```

Expected: no output. The new 2026-07-10 design and plan are excluded because they intentionally describe removing `/cancel`.

- [ ] **Step 3: Run focused tests**

```bash
mvn test -Dtest='EscapeControllerTest,ReplLoopEscapeTest,AgentLoopTest,SseStreamReaderTest,AppCommandRegistryTest' -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: zero failures and zero errors.

- [ ] **Step 4: Run the full suite**

```bash
mvn test -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Build the shaded executable**

```bash
mvn package -DskipTests
```

Expected: BUILD SUCCESS and `target/maple-code-java-0.1.0.jar` exists.

- [ ] **Step 6: Review final diff and workspace state**

```bash
git diff --check
git status --short
git diff --stat HEAD
```

Expected: only intended source, test, plan, and documentation changes; preserve the pre-existing untracked `docs/review/2026-07-09-slash-command-review.md`.

- [ ] **Step 7: Commit documentation**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-07-09-maple-code-command-framework-design.md docs/superpowers/specs/2026-07-09-maple-code-tui-status-bar-design.md
git commit -m "docs: document escape controls"
```

---

## Completion checklist

- [ ] Every new behavior was introduced by a test observed failing first.
- [ ] Single Esc cancels at the next stream chunk boundary with `USER_CANCELLED`.
- [ ] Pending tools do not execute and partial assistant/tool messages do not persist.
- [ ] A cancelled run does not poison the next run.
- [ ] Double Esc clears normal input and aborts an entire multiline block within 500ms.
- [ ] Arrow-key Escape sequences remain bound.
- [ ] The listener is inactive during tool execution and HITL prompts.
- [ ] `/cancel` is absent from registry, help, completion, source, tests, and active docs.
- [ ] Cancelled normal turns do not trigger memory extraction.
- [ ] Focused tests, full tests, and the shaded package build pass.
