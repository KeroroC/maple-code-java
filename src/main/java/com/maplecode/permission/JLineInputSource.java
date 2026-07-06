package com.maplecode.permission;

import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

public final class JLineInputSource implements InputSource {
    private final LineReader reader;

    public JLineInputSource(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public String readLine(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException e) {
            throw new RuntimeException(e);
        }
    }
}
