package utils.history.dto;

import java.util.ArrayList;
import java.util.List;

public class HistoricalTrendDto {
    public int    latestScore;
    public int    delta;                          // signed: positive = improving
    public double movingAverage;
    public String trend = "STABLE";               // IMPROVING | DEGRADING | STABLE
    public int    windowSize;
    public boolean anomalyDetected;
    public String anomalyDescription;
    public List<Integer> scores = new ArrayList<>();
    public List<String>  runIds = new ArrayList<>();
}
