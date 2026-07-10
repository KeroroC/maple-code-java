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
