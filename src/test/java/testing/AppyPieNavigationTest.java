package testing;

import java.util.logging.Level;

import org.openqa.selenium.By;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.Test;

import base.BaseTest;
import pages.AppyPieAutomatePage;
import pages.AppyPieHomePage;
import pages.ConnectTopNavigation;
import utils.ConsoleLogFilter;
import utils.HealthTracker;
import utils.TrendExporter;

public class AppyPieNavigationTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(AppyPieNavigationTest.class);

	private void captureBrowserLogs(String context) {
		try {
			LogEntries logs = driver.manage().logs().get(LogType.BROWSER);
			for (LogEntry entry : logs) {
				String msg = entry.getMessage();
				String lower = msg == null ? "" : msg.toLowerCase();

				// 1) Auth iframe / frame-ancestors / FedCM / accounts.google.com / clarity.ms → IGNORE
				if (lower.contains("frame-ancestors") || lower.contains("accounts.google.com") || lower.contains("clarity.ms") || lower.contains("fedcm")) {
					// Ignore auth/identity provider related messages entirely (do not record or penalize)
					continue;
				}

				// 2) Path contains /blog/ -> ignore
				if (ConsoleLogFilter.isBlogPathLog(msg)) {
					continue;
				}

				// 3) Analytics/tracking duplication → LOW warning
				if (ConsoleLogFilter.isAnalyticsLog(msg)) {
					HealthTracker.get().addWarning("Analytics", "Tracking: " + msg);
					continue;
				}

				// 4) CDN/plugin → LOW unless blocks UI
				if (ConsoleLogFilter.isCdnPluginLog(msg)) {
					if (ConsoleLogFilter.isBlockingForUI(msg)) {
						// Treat as JS error / HIGH severity because it blocks UI
						HealthTracker.get().recordJsError(context, msg);
					} else {
						// Downgrade to LOW warning
						HealthTracker.get().addWarning("CDN/Plugin", "Resource issue: " + msg);
					}
					continue;
				}

				// Default: existing behavior
				if (ConsoleLogFilter.isThirdPartyAuthLog(msg)) {
					HealthTracker.get().recordThirdPartyAuth(context, msg);
					continue;
				}
				if (ConsoleLogFilter.isIgnoredLog(msg)) continue; // ignore other known benign/organizational messages
				if (entry.getLevel().intValue() >= Level.SEVERE.intValue()) {
					HealthTracker.get().recordJsError(context, msg);
				}
			}
		} catch (Exception e) {
			HealthTracker.get().addWarning("JS Logs", "Unable to capture logs on " + context);
		}
	}

	@Test(groups = "navigation", priority = 1)
	public void openHomePage() {
		driver.get("https://www.appypie.com");

		String title = driver.getTitle().toLowerCase();
		// Be tolerant to small copy changes in page title; assert presence of product/site keywords
		if (!(title.contains("appy pie") || title.contains("app builder") || title.contains("appypie"))) {
			HealthTracker.get().addWarning("HomePage", "Title unexpected: '" + driver.getTitle() + "'");
		}
		captureBrowserLogs("HomePage");
	}

	@Test(groups = "navigation", priority = 2)
	public void validateAutomateUX() {
		try {
			AppyPieHomePage home = new AppyPieHomePage(driver);
			home.navigateToAutomate();
			captureBrowserLogs("NavigateToAutomate");

			AppyPieAutomatePage automate = new AppyPieAutomatePage(driver);
			automate.isFullyLoaded();
			captureBrowserLogs("AutomatePageLoaded");

			long lcp = automate.getLCP();
			double cls = automate.getCLS();
			LOG.info("LCP: {}ms | CLS: {}", lcp, cls);

			if (lcp == -1) {
				HealthTracker.get().addWarning("Performance", "LCP unavailable");
			} else if (lcp > 3000) {
				HealthTracker.get().addWarning("Performance", "Slow LCP: " + lcp + " ms");
			}

			By section = By.xpath("//h2[contains(text(),'Popular Automations')]");
			try {
				automate.scrollToElement(section);
				captureBrowserLogs("ScrollCheck");
			} catch (Exception ignore) {
				HealthTracker.get().addWarning("UX", "Popular Automations missing/unreachable");
			}

			ConsoleLogFilter.ignoreKnownErrors(driver);

		} catch (Exception ex) {
			HealthTracker.get().addWarning("AutomateUX", "Failure: " + ex.getMessage());
		}
	}

	@Test(groups = "navigation", priority = 3)
	public void connectTopNavigationTest() {
		ConnectTopNavigation nav = new ConnectTopNavigation(driver);

		navigateAndLog("Features", nav.goToFeatures());
		navigateAndLog("App Directory", nav.goToAppDirectory());
		navigateAndLog("AI Automation", nav.goToAIAutomation());
		navigateAndLog("AI Connects", nav.goToAIConnects());
		navigateAndLog("MCP Server", nav.goToMCPServer());
		navigateAndLog("Pricing", nav.goToPricing());
		navigateAndLog("Blog", nav.goToBlog());
		navigateAndLog("Contact Sales", nav.goToContactSales());
		navigateAndLog("Signup", nav.goToSignup());
		navigateAndLog("Login", nav.goToLogin());
	}

	private void navigateAndLog(String label, long time) {
		LOG.info("{} load time: {} ms", label, time);
		captureBrowserLogs(label);
	}

	@AfterSuite
	public void afterSuiteHealthReport() {
		HealthTracker tracker = HealthTracker.get();
		tracker.printReport();

		// Launch dashboard (overwrite mode)
		TrendExporter.updateTrend(tracker.getScore());
	}

}
