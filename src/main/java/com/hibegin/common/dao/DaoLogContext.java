package com.hibegin.common.dao;

/**
 * Thread-scoped label for DAO logs. Host applications can open a scope before
 * running plugin/user work so SQL diagnostics can identify the caller.
 */
public final class DaoLogContext {

    private static final ThreadLocal<String> CURRENT_LABEL = new ThreadLocal<>();

    private DaoLogContext() {
    }

    public static Scope open(String label) {
        String previous = CURRENT_LABEL.get();
        String normalized = normalize(label);
        if (normalized == null) {
            CURRENT_LABEL.remove();
        } else {
            CURRENT_LABEL.set(normalized);
        }
        return new Scope(previous);
    }

    public static String currentLabel() {
        return CURRENT_LABEL.get();
    }

    public static String format(String message) {
        String label = currentLabel();
        if (label == null) {
            return message;
        }
        return "[" + label + "] " + message;
    }

    private static String normalize(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT_LABEL.remove();
            } else {
                CURRENT_LABEL.set(previous);
            }
        }
    }
}
