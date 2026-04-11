package org.bdj;

public class Status {
    private static RemoteLogger LOGGER;
    private static volatile boolean WINDOWBOOL = false;
    private static volatile boolean LOGGERBOOL = false;
    // private static volatile String LOGGER_ERROR;

    public static void setScreenOutputEnabled(boolean windowbool) {
        WINDOWBOOL = windowbool;
    }

    public static void setNetworkLoggerEnabled(boolean networkbool) {
        LOGGERBOOL = networkbool;
    }

    private static synchronized void initLogger() {
        if (LOGGER == null || !LOGGER.isRunning()) {
            LOGGER = new RemoteLogger(18194, 1000);
            if (LOGGER.start()) {
                // LOGGER_ERROR = null;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Thread.currentThread().interrupt();
                }
            } // else {
                // Throwable error = LOGGER.getStartupError();
                // LOGGER_ERROR = error == null ? "unknown startup failure" : error.toString();
            // }
        }
    }

    public static void close() {
        synchronized (Status.class) {
            if (LOGGER != null) {
                LOGGER.stop();
                LOGGER = null;
            }
        }
    }

    public static void println(String msg, boolean showOnScreen) {
        String finalMsg = "[" + Thread.currentThread().getName() + "] " + msg;
        if (LOGGERBOOL) {
            initLogger();
            if (LOGGER != null && LOGGER.isRunning()) {
                LOGGER.println(finalMsg);
            }
        }
        if (WINDOWBOOL && showOnScreen) {
            Screen.println(finalMsg);
            // if (LOGGERBOOL && LOGGER_ERROR != null) {
                // Screen.println("[logger] RemoteLogger start failed: " + LOGGER_ERROR);
                // LOGGER_ERROR = null;
            // }
        }
    }

    public static void println(String msg) {
        println(msg, true);
    }

    public static void printStackTrace(String msg, Throwable e, boolean showOnScreen) {
        String finalMsg = "[" + Thread.currentThread().getName() + "] " + msg;
        if (LOGGERBOOL) {
            initLogger();
            if (LOGGER != null && LOGGER.isRunning()) {
                LOGGER.printStackTrace(finalMsg, e);
            }
        }
        if (WINDOWBOOL && showOnScreen) {
            Screen.printStackTrace(finalMsg, e);
            // if (LOGGERBOOL && LOGGER_ERROR != null) {
                // Screen.println("[logger] RemoteLogger start failed: " + LOGGER_ERROR);
                // LOGGER_ERROR = null;
            // }
        }
    }

    public static void printStackTrace(String msg, Throwable e) {
        printStackTrace(msg, e, true);
    }
}
