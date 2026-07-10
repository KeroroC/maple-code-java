package com.maplecode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EscapeController implements AutoCloseable {
    public static final long DOUBLE_ESC_TIMEOUT_MS = 500L;
    static final String NOOP_WIDGET = "maple-escape-noop";
    static final String CLEAR_WIDGET = "maple-clear-input";

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
