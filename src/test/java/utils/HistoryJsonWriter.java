package utils;

import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class HistoryJsonWriter {

    public static void writeJson() throws Exception {
        Path csv = Paths.get("reports/trend/health_history.csv");
        Path out = Paths.get("reports/trend/history.json");

        List<String> lines = Files.readAllLines(csv);
        List<String> json = new ArrayList<>();

        json.add("[");
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",");
            json.add(String.format(
                "{\"run\":%s,\"score\":%s}",
                p[0], p[1]
            ) + (i < lines.size() - 1 ? "," : ""));
        }
        json.add("]");

        Files.write(out, json, StandardCharsets.UTF_8);
    }
}
