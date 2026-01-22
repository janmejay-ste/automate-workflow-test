package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class DashboardBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(DashboardBuilder.class);
    private static final String BASE = "reports/trend";
    private static final String DASHBOARD = BASE + "/dashboard.html";

    public static void build(int score, int failed, int running, int healthy, List<String> slowPages, List<TestNgResultParser.TestMethodResult> methods) {
        try {
            Files.createDirectories(Paths.get(BASE));
        } catch (Exception ex) {
            LOG.debug("Failed to create base dir: {}", ex.getMessage());
        }

        String slowSection = buildSlowSection(slowPages);
        String template = """
        <html><head><meta charset="utf-8"><title>Health Dashboard</title>
        <style>
        body { background:#f6f7fb; color:#0f172a; font-family:Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; margin:0; padding:28px; }
        .container { max-width:1200px; margin:0 auto; }
        .header { display:flex; align-items:center; justify-content:space-between; }
        .h-title { font-size:26px; margin:0; color:#0f172a; }
        .score { font-size:18px; color:#0ea57a; font-weight:600; }
        .grid { display:grid; grid-template-columns:repeat(3, 1fr); gap:20px; margin-top:18px; }
        .card { background:#ffffff; border-radius:14px; box-shadow:0 6px 18px rgba(15,23,42,0.06); padding:18px; }
        .card .meta { display:flex; align-items:center; justify-content:space-between; }
        .title { color:#64748b; font-size:13px; margin:0; }
        .value { font-size:30px; font-weight:700; margin-top:6px; color:#0f172a; }
        .status { padding:8px; border-radius:10px; display:inline-flex; align-items:center; justify-content:center; width:44px; height:44px; }
        .status.error { color:#b91c1c; background:#fff1f2; }
        .status.active { color:#b45309; background:#fff7ed; }
        .status.success { color:#065f46; background:#ecfdf5; }
        .action { margin-top:12px; font-size:13px; color:#2563eb; text-decoration:none; display:inline-block; }
        .activity { margin-top:22px; background:#fff; padding:18px; border-radius:12px; box-shadow:0 6px 18px rgba(15,23,42,0.04); }
        .activity h2 { margin:0 0 8px 0; font-size:16px; }
        .activity p { color:#64748b; margin:0 0 12px 0; font-size:13px; }
        .row { display:flex; justify-content:space-between; font-size:14px; padding:8px 0; border-bottom:1px solid #eef2ff; }
        .row:last-child { border-bottom:none; }
        .trend { margin-top:24px; text-align:center; }
        img.trend-img { width:100%; max-width:1100px; height:auto; border-radius:10px; box-shadow:0 10px 30px rgba(2,6,23,0.08); display:block; margin:12px auto 0; }
        @media (max-width:900px){ .grid{grid-template-columns:1fr;} }
        </style>
        </head><body>
        <div class="container">
          <div class="header">
            <h1 class="h-title">System Overview</h1>
            <div class="score">Score: {{score}} / 100</div>
          </div>

          <div class="grid">
            <div class="card">
              <div class="meta">
                <div>
                  <div class="title">Failed Automations</div>
                  <div class="value">{{failed}}</div>
                </div>
                <div class="status error">!</div>
              </div>
              <a class="action" href="failures.html">View failures</a>
            </div>

            <div class="card">
              <div class="meta">
                <div>
                  <div class="title">Running</div>
                  <div class="value">{{running}}</div>
                </div>
                <div class="status active">⏳</div>
              </div>
              <a class="action" href="runs.html">View runs</a>
            </div>

            <div class="card">
              <div class="meta">
                <div>
                  <div class="title">Healthy</div>
                  <div class="value">{{healthy}}</div>
                </div>
                <div class="status success">✓</div>
              </div>
              <a class="action" href="all.html">View all</a>
            </div>
          </div>

          {{slowSection}}

          <div class="trend">
            <img class="trend-img" src="trend.png" alt="trend">
          </div>
        </div>
        </body></html>
        """;

        // Use escaped placeholders for all dynamic values
        String html = template.replace("{{slowSection}}", slowSection)
                .replace("{{score}}", escapeHtml(String.valueOf(score)))
                .replace("{{failed}}", escapeHtml(String.valueOf(failed)))
                .replace("{{running}}", escapeHtml(String.valueOf(running)))
                .replace("{{healthy}}", escapeHtml(String.valueOf(healthy)));

        try {
            Files.write(Paths.get(DASHBOARD), html.getBytes());
        } catch (Exception ex) {
            LOG.debug("Failed writing dashboard: {}", ex.getMessage());
        }

        buildFailures(methods);
        buildRuns(methods);
        buildAll();
    }

    private static String escapeHtml(String in) {
        if (in == null) return "";
        String s = in;
        // Order matters: ampersand first
        s = s.replace("&", "&amp;");
        s = s.replace("<", "&lt;");
        s = s.replace(">", "&gt;");
        s = s.replace("\"", "&quot;");
        s = s.replace("'", "&#x27;");
        s = s.replace("/", "&#x2F;");
        return s;
    }

    private static String buildSlowSection(List<String> slowPages) {
        if (slowPages == null || slowPages.isEmpty()) return "";
        StringBuilder slowHtml = new StringBuilder();
        slowHtml.append("<div class='activity'><h2>Slow Pages</h2><p>Pages with load time above threshold</p>");
        slowHtml.append("<table style='width:100%;border-collapse:collapse'><thead><tr><th>Page</th><th>Load (ms)</th><th>Severity</th></tr></thead><tbody>");
        for (String s : slowPages) {
            String safe = escapeHtml(s);
            slowHtml.append("<tr><td>").append(safe).append("</td><td>-")
                    .append("</td><td>" ).append("-" ).append("</td></tr>");
        }
        slowHtml.append("</tbody></table></div>");
        return slowHtml.toString();
    }

    private static void buildFailures(List<TestNgResultParser.TestMethodResult> methods) {
        try {
            StringBuilder b = new StringBuilder();
            b.append("<html><head><meta charset=\"utf-8\"><title>Failures</title>");
            b.append("<style>body{font-family:Arial;margin:16px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:8px}</style>");
            b.append("</head><body><h1>Failed Tests</h1>");
            b.append("<p><a href=\"dashboard.html\">Back to dashboard</a></p>");
            b.append("<table><tr><th>Source</th><th>Detail</th><th>Time (ms)</th></tr>");
            boolean any = false;
            for (TestNgResultParser.TestMethodResult m : methods) {
                if (m.status != null && m.status.equalsIgnoreCase("FAIL")) {
                    any = true;
                    String cls = escapeHtml(m.className);
                    String meth = escapeHtml(m.methodName);
                    b.append("<tr><td>TestNG</td><td>").append(cls).append("::").append(meth).append("</td><td>").append(m.durationMs).append("</td></tr>");
                }
            }
            if (!any) b.append("<tr><td colspan=\"3\">No failures recorded</td></tr>");
            b.append("</table></body></html>");
            Files.write(Paths.get(BASE, "failures.html"), b.toString().getBytes());
        } catch (Exception ex) {
            LOG.debug("Failed building failures page: {}", ex.getMessage());
        }
    }

    private static void buildRuns(List<TestNgResultParser.TestMethodResult> methods) {
        try {
            StringBuilder b = new StringBuilder();
            b.append("<html><head><meta charset=\"utf-8\"><title>Runs</title>");
            b.append("<style>body{font-family:Arial;margin:16px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:8px}</style>");
            b.append("</head><body><h1>Recent Runs</h1>");
            b.append("<p><a href=\"dashboard.html\">Back to dashboard</a></p>");
            b.append("<table><tr><th>Class</th><th>Method</th><th>Status</th><th>Time (ms)</th></tr>");
            if (methods == null || methods.isEmpty()) {
                b.append("<tr><td colspan=\"4\">No runs available</td></tr>");
            } else {
                for (TestNgResultParser.TestMethodResult m : methods) {
                    String cls = escapeHtml(m.className);
                    String meth = escapeHtml(m.methodName);
                    String status = escapeHtml(m.status);
                    String color = "#000";
                    if ("FAIL".equalsIgnoreCase(m.status)) color = "#b91c1c";
                    else if ("PASS".equalsIgnoreCase(m.status)) color = "#10B981";
                    b.append("<tr><td>").append(cls).append("</td><td>").append(meth).append("</td><td style=\"color:").append(color).append("\">").append(status).append("</td><td>").append(m.durationMs).append("</td></tr>");
                }
            }
            b.append("</table></body></html>");
            Files.write(Paths.get(BASE, "runs.html"), b.toString().getBytes());
        } catch (Exception ex) {
            LOG.debug("Failed building runs page: {}", ex.getMessage());
        }
    }

    private static void buildAll() {
        try {
            Path src = Paths.get("test-output/emailable-report.html");
            Path dest = Paths.get(BASE, "all.html");
            if (Files.exists(src)) {
                Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                StringBuilder b = new StringBuilder();
                b.append("<html><head><meta charset=\"utf-8\"><title>All Reports</title></head><body>");
                b.append("<h1>All Reports</h1><p><a href=\"../test-output/emailable-report.html\">Open TestNG report</a></p>");
                b.append("<p><a href=\"dashboard.html\">Back to dashboard</a></p></body></html>");
                Files.write(dest, b.toString().getBytes());
            }
        } catch (Exception ex) {
            LOG.debug("Failed building all page: {}", ex.getMessage());
        }
    }
}
