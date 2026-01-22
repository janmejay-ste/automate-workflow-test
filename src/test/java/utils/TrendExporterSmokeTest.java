package utils;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TrendExporterSmokeTest {

    @Test
    public void smokeRunGeneratesDashboardAndChart() throws Exception {
        System.setProperty("trend.open", "false");
        // run twice to ensure append mode works
        TrendExporter.updateTrend(70);
        TrendExporter.updateTrend(80);

        Path dashboard = Paths.get("reports/trend/dashboard.html");
        Path chart = Paths.get("reports/trend/trend.png");

        Assert.assertTrue(Files.exists(dashboard), "dashboard.html should be created");
        Assert.assertTrue(Files.exists(chart), "trend.png should be created");

        // verify CSV contains at least two entries
        Path csv = Paths.get("reports/trend/health_history.csv");
        Assert.assertTrue(Files.exists(csv), "health_history.csv should exist");
        long lines = Files.readAllLines(csv).stream().filter(s->s!=null && !s.trim().isEmpty()).count();
        Assert.assertTrue(lines >= 2, "CSV should have at least 2 non-empty lines (header + rows)");
    }
}
