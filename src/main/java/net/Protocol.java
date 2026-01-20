package net;

public class Protocol {
    public static String make(String type, String payload) {
        if (payload == null) payload = "";
        return type + ':' + payload + "\n";
    }

    public static Parsed parse(String line) {
        if (line == null) return null;
        int idx = line.indexOf(':');
        if (idx < 0) return new Parsed(line.trim(), "");
        String t = line.substring(0, idx).trim();
        String p = line.substring(idx + 1).trim();
        return new Parsed(t, p);
    }

    public static class Parsed {
        public final String type;
        public final String payload;
        public Parsed(String type, String payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
