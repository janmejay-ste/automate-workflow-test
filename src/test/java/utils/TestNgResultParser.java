package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class TestNgResultParser {
    private static final Logger LOG = LoggerFactory.getLogger(TestNgResultParser.class);
    private static final String TESTNG_RESULTS_MAVEN = "target/surefire-reports/testng-results.xml";
    private static final String TESTNG_RESULTS_IDE = "test-output/testng-results.xml";

    public static class TestMethodResult {
        public final String className;
        public final String methodName;
        public final String status;
        public final long durationMs;

        public TestMethodResult(String className, String methodName, String status, long durationMs) {
            this.className = className;
            this.methodName = methodName;
            this.status = status;
            this.durationMs = durationMs;
        }
    }

    public static List<TestMethodResult> parse() {
        List<TestMethodResult> out = new ArrayList<>();
        try {
            Path p = Paths.get(TESTNG_RESULTS_MAVEN);
            if (!Files.exists(p)) {
                p = Paths.get(TESTNG_RESULTS_IDE);
            }
            if (!Files.exists(p))
                return out;
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(p.toFile());
            NodeList methods = doc.getElementsByTagName("test-method");
            for (int i = 0; i < methods.getLength(); i++) {
                if (!(methods.item(i) instanceof Element))
                    continue;
                Element el = (Element) methods.item(i);
                String name = el.getAttribute("name");
                String status = el.getAttribute("status");
                String duration = el.getAttribute("duration-ms");
                long dur = 0L;
                try {
                    dur = Long.parseLong(duration);
                } catch (Exception ignored) {
                }
                // find enclosing class element safely
                String className = "";
                org.w3c.dom.Node parent = el.getParentNode();
                while (parent != null && parent instanceof Element) {
                    Element pEl = (Element) parent;
                    if ("class".equals(pEl.getTagName())) {
                        className = pEl.getAttribute("name");
                        break;
                    }
                    parent = parent.getParentNode();
                }
                out.add(new TestMethodResult(className, name, status, dur));
            }
        } catch (Exception ex) {
            LOG.debug("Failed to parse testng-results.xml: {}", ex.getMessage());
        }
        return out;
    }
}
