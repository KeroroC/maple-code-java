package com.maplecode.prompt;

public final class ReminderMessage {
    public static final String TAG_OPEN = "<system-reminder>";
    public static final String TAG_CLOSE = "</system-reminder>";

    private ReminderMessage() {}

    public static String wrap(String body) {
        return TAG_OPEN + "\n" + body + "\n" + TAG_CLOSE;
    }
}
