package utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.format.DateTimeFormatter;

public class TrendExporterRunner {
    public static void main(String[] args) {
        int score = 29;
        if (args != null && args.length > 0) {
            try { score = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        TrendExporter.updateTrend(score);
        System.out.println("TrendExporter.run completed with score=" + score);
        try {
            Path p = Paths.get("reports/trend/dashboard.html");
            System.out.println("Dashboard path (relative): " + p.toString());
            System.out.println("Dashboard exists: " + Files.exists(p));
            if (Files.exists(p)) {
                System.out.println("Dashboard absolute: " + p.toAbsolutePath());
                FileTime ft = Files.getLastModifiedTime(p);
                String t = DateTimeFormatter.ISO_INSTANT.format(ft.toInstant());
                System.out.println("Dashboard lastModified: " + t);
                System.out.println("Dashboard size bytes: " + Files.size(p));
            }
        } catch (Exception ex) {
            System.out.println("Error querying dashboard file: " + ex.getMessage());
        }
    }
}