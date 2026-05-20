package utils.governance.dto;

/**
 * Tracks how many times a specific alert or retry was suppressed, and whether
 * suppression was eventually validated (the thing was genuinely noise) or
 * found to have hidden a real problem.
 */
public class SuppressionRecordDto {
    public String  suppressionKey;      // unique key: signalType + target
    public String  target;              // workflow or test
    public String  suppressionReason;   // why suppressed (e.g. "CASCADE_DOWNSTREAM")
    public int     timesSuppressed;     // total suppression count
    public String  firstSuppressed;     // ISO-8601 timestamp
    public String  lastSuppressed;      // ISO-8601 timestamp

    // Outcome tracking (populated when overridden or validated)
    public boolean eventuallyValidated; // true = suppression was correct (noise confirmed)
    public boolean hidRealRegression;   // true = suppression masked a real failure
    public int     validatedCount;      // how many times validated as noise
    public int     falseSuppressionCount; // how many times it hid a real issue
}
