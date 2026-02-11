package utils;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.style.Styler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TrendChartRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(TrendChartRenderer.class);
    private static final String BASE = "reports/trend";

    public static void render(List<Integer> scores) {
        try {
            if (scores == null) scores = java.util.Collections.emptyList();
            double[] x = new double[scores.size()];
            double[] y = new double[scores.size()];
            for (int i = 0; i < scores.size(); i++) {
                x[i] = i + 1;
                y[i] = scores.get(i);
            }
            // If no scores, render a simple empty chart with a single zero point
            if (x.length == 0) {
                x = new double[]{1};
                y = new double[]{0};
            }

            XYChart chart = new XYChartBuilder()
                    .width(1200)
                    .height(600)
                    .title("Health Score Trend")
                    .xAxisTitle("Run")
                    .yAxisTitle("Score")
                    .theme(Styler.ChartTheme.GGPlot2)
                    .build();

            chart.getStyler().setChartBackgroundColor(new Color(250,250,250));
            chart.getStyler().setPlotBackgroundColor(Color.WHITE);
            chart.getStyler().setPlotGridLinesVisible(true);
            chart.getStyler().setLegendVisible(false);
            chart.getStyler().setSeriesColors(new Color[]{new Color(16,185,129)});
            chart.getStyler().setMarkerSize(6);
            chart.getStyler().setDefaultSeriesRenderStyle(org.knowm.xchart.XYSeries.XYSeriesRenderStyle.Line);

            chart.addSeries("Score", x, y).setLineWidth(3f);
            Path out = Paths.get(BASE, "trend.png");
            // Save with higher DPI for crisp rendering
            BitmapEncoder.saveBitmapWithDPI(chart, out.toString().replaceAll("\\.png$",""), BitmapEncoder.BitmapFormat.PNG, 200);
        } catch (Exception ex) {
            LOG.warn("Failed rendering chart: {}", ex.getMessage());
        }
    }
}