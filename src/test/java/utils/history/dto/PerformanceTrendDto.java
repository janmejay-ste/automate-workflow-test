package utils.history.dto;

public class PerformanceTrendDto {
    public String  page;
    public long    latestLoadMs;
    public long    avgLoadMs;
    public long    maxLoadMs;
    public long    minLoadMs;
    public long    baselineMs;     // first recorded load time for this page
    public double  degradationPct; // ((latest - baseline) / baseline) * 100
    public boolean isDegraded;     // degradationPct > 50
    public int     entryCount;
}
