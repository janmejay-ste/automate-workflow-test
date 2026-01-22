package utils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class SnapshotReader {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotReader.class);
    private static final String SNAPSHOT = "reports/trend/health_snapshot.json";

    public static Optional<JSONObject> readSnapshot() {
        Path p = Paths.get(SNAPSHOT);
        if (!Files.exists(p)) return Optional.empty();
        try (Reader r = Files.newBufferedReader(p)) {
            JSONParser parser = new JSONParser();
            Object o = parser.parse(r);
            if (o instanceof JSONObject) return Optional.of((JSONObject) o);
        } catch (Exception ex) {
            LOG.debug("Failed to read/parse snapshot json: {}", ex.getMessage());
        }
        return Optional.empty();
    }

    public static List<String> getArray(String key) {
        try {
            Optional<JSONObject> snap = readSnapshot();
            if (snap.isEmpty()) return Collections.emptyList();
            Object o = snap.get().get(key);
            if (o instanceof JSONArray) {
                List<String> out = new ArrayList<>();
                for (Object item : (JSONArray) o) {
                    if (item == null) continue;
                    out.add(item.toString());
                }
                return out;
            }
        } catch (Exception ex) {
            LOG.debug("Error reading array {}: {}", key, ex.getMessage());
        }
        return Collections.emptyList();
    }

    public static List<String> getSlowPages() {
        // Prefer typed JSON access over brittle string parsing
        return getArray("slowPagesDetails");
    }
}
