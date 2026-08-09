package com.termux.shared.data;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ExternalUrlPolicy {

    public enum Action { OPEN, CONFIRM, BLOCK }

    public static final class Decision {
        public final Action action;
        public final String scheme;

        private Decision(Action action, String scheme) {
            this.action = action;
            this.scheme = scheme;
        }
    }

    private static final int MAX_URL_CHARS = 2048;
    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]{0,31}");
    private static final Pattern WINDOWS_PATH = Pattern.compile("^[a-zA-Z]:");
    private static final Set<String> BLOCKED_SCHEMES = new HashSet<>(Arrays.asList(
        "about", "android-app", "blob", "cmd", "content", "data", "file",
        "intent", "javascript", "ms-appinstaller", "ms-msdt", "ms-settings",
        "package", "powershell", "shell", "ssh", "termux", "wtmux"
    ));

    private ExternalUrlPolicy() {}

    public static Decision classify(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_CHARS || WINDOWS_PATH.matcher(value).find()) {
            return new Decision(Action.BLOCK, "");
        }
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (Character.isHighSurrogate(item)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return new Decision(Action.BLOCK, "");
                }
                index++;
                continue;
            }
            if (Character.isLowSurrogate(item)) return new Decision(Action.BLOCK, "");
            if (item == '%' && (index + 2 >= value.length() || !isHex(value.charAt(index + 1)) ||
                !isHex(value.charAt(index + 2)))) return new Decision(Action.BLOCK, "");
            if (item == '\\' || item == '\uFEFF' || Character.isISOControl(item) ||
                Character.isWhitespace(item) || Character.isSpaceChar(item)) {
                return new Decision(Action.BLOCK, "");
            }
        }

        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            return new Decision(Action.BLOCK, "");
        }
        String scheme = uri.getScheme();
        if (scheme == null) return new Decision(Action.BLOCK, "");
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!SCHEME.matcher(scheme).matches() || BLOCKED_SCHEMES.contains(scheme)) {
            return new Decision(Action.BLOCK, scheme);
        }
        if (uri.getRawUserInfo() != null) return new Decision(Action.BLOCK, scheme);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (!validWebAuthority(value, scheme) || uri.getHost() == null || uri.getHost().isEmpty()) {
                return new Decision(Action.BLOCK, scheme);
            }
            return new Decision(Action.OPEN, scheme);
        }
        return new Decision(Action.CONFIRM, scheme);
    }

    private static boolean validWebAuthority(String value, String scheme) {
        String prefix = scheme + "://";
        if (value.length() < prefix.length() || !value.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        String remainder = value.substring(prefix.length());
        int end = remainder.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int candidate = remainder.indexOf(delimiter);
            if (candidate >= 0 && candidate < end) end = candidate;
        }
        String authority = remainder.substring(0, end);
        if (authority.isEmpty() || authority.indexOf('@') >= 0) return false;
        String port = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close <= 1) return false;
            String host = authority.substring(1, close);
            if (host.indexOf(':') < 0 || !host.matches("[0-9A-Fa-f:.]+")) return false;
            String suffix = authority.substring(close + 1);
            if (!suffix.isEmpty() && !suffix.startsWith(":")) return false;
            if (!suffix.isEmpty()) port = suffix.substring(1);
        } else {
            int colon = authority.indexOf(':');
            if (colon != authority.lastIndexOf(':')) return false;
            String host = colon < 0 ? authority : authority.substring(0, colon);
            if (colon >= 0) port = authority.substring(colon + 1);
            if (host.length() > 253 || !host.matches("[A-Za-z0-9.-]+")) return false;
            String[] labels = host.split("\\.", -1);
            boolean numeric = true;
            for (String label : labels) {
                if (label.isEmpty() || label.length() > 63 ||
                    !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) return false;
                if (!label.matches("[0-9]+")) numeric = false;
            }
            if (numeric) {
                if (labels.length != 4) return false;
                for (String label : labels) if (label.length() > 3 || Integer.parseInt(label) > 255) return false;
            }
        }
        if (port == null) return true;
        if (port.isEmpty() || !port.matches("[0-9]+") || port.length() > 5) return false;
        return Integer.parseInt(port) <= 65535;
    }

    private static boolean isHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f' || value >= 'A' && value <= 'F';
    }
}
