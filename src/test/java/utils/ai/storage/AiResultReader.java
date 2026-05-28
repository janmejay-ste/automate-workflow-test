package utils.ai.storage;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.ai.models.AiFailureAnalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class AiResultReader {
    private static final Logger LOG = LoggerFactory.getLogger(AiResultReader.class);

    private AiResultReader() {}

    public static List<AiFailureAnalysis> loadAll(String directory) {
        List<AiFailureAnalysis> results = new ArrayList<>();
        Path dir = Paths.get(directory);

        if (!Files.exists(dir)) return results;

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    String content = Files.readString(path);
                    JSONObject json = (JSONObject) new JSONParser().parse(content);
                    results.add(AiResultWriter.fromJson(json));
                } catch (Exception e) {
                    LOG.warn("Failed to read AI result from {}: {}", path.getFileName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.warn("Failed to list AI results in {}: {}", directory, e.getMessage());
        }

        return results;
    }
}
