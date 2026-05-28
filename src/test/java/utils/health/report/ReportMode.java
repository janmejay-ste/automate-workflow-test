package utils.health.report;

/**
 * Which PDF report to produce.
 *
 *   EXECUTIVE — 5–8 pages, narrative-led, release-decision focused.
 *               Read by managers, release reviewers, stakeholders.
 *   TECHNICAL — 20–50 pages, telemetry-rich, evidence-led.
 *               Read by QA, engineering, DevOps for forensic drill-down.
 */
public enum ReportMode {
    EXECUTIVE,
    TECHNICAL
}
