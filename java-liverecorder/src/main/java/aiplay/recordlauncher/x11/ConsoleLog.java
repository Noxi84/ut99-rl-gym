package aiplay.recordlauncher.x11;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ConsoleLog {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ConsoleLog() {
    }

    public static void info(String message) {
        System.out.println(format(message));
    }

    public static void warn(String message) {
        System.out.println(format("⚠️ " + message));
    }

    public static void error(String message) {
        System.err.println(format("❌ " + message));
    }

    private static String format(String message) {
        return "[" + LocalDateTime.now().format(TS) + "] " + message;
    }
}
