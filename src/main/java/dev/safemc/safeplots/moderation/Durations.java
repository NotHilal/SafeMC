package dev.safemc.safeplots.moderation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and prints durations like {@code 30m}, {@code 2h}, {@code 7d}, {@code 1w}, {@code 1d12h}. */
public final class Durations {
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])");

    private Durations() {}

    /** Milliseconds, or -1 if the text isn't a valid duration. */
    public static long parse(String text) {
        String s = text.toLowerCase();
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return -1;
            }
            long n = Long.parseLong(m.group(1));
            total += n * switch (m.group(2)) {
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 604_800_000L; // w
            };
            end = m.end();
        }
        return end == s.length() && total > 0 ? total : -1;
    }

    public static String format(long millis) {
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (mins > 0 && days == 0) sb.append(mins).append("m");
        return sb.toString().trim();
    }
}
