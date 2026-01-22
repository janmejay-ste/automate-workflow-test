package pages;

import java.time.Duration;
import java.util.Map;

import org.openqa.selenium.*;
import utils.HealthTracker;
import utils.WaitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectTopNavigation {

	private WebDriver driver;
	private WaitUtils waits;
    private static final Logger LOG = LoggerFactory.getLogger(ConnectTopNavigation.class);

	public ConnectTopNavigation(WebDriver driver) {
		this.driver = driver;
		this.waits = new WaitUtils(driver, Duration.ofSeconds(15));
	}

	// Use a more resilient selector for the Features link (link text or href fragment)
	private By featureBtn = By.linkText("Features");
	private By appDirectoryBtn = By.id("app-directory");
	private By aiAutomationBtn = By.cssSelector("a[href*='ai-automation']");
	private By aiConnectsBtn = By.cssSelector("a[href*='ai-connects']");
	private By mcpServerBtn = By.cssSelector("a[href*='mcp-server']");
	private By pricingBtn = By.linkText("Pricing");
	private By blogBtn = By.cssSelector("a[href*='blog']");
	private By contactSalesBtn = By.linkText("Contact Sales");
	private By signupBtn = By.cssSelector("a[title='Sign Up']");
	private By loginBtn = By.linkText("Log In");

	private Map<String, String> fallbackUrls = Map.of("features", "https://www.appypieautomate.ai/integrate/features",
			"app-directory", "https://www.appypieautomate.ai/integrate/app-directory", "automation",
			"https://www.appypieautomate.ai/integrate/ai-workflow-builder", "ai-connects",
			"https://www.appypieautomate.ai/ai-connects/", "mcp-server", "https://www.appypieautomate.ai/mcp/",
			"pricing", "https://www.appypieautomate.ai/integrate/pricing-plan", "blog",
			"https://www.appypieautomate.ai/blog/", "contact",
			"https://calendly.com/d/cnsb-yh8-y2j/integration-specialist-team", "signup",
			"https://accounts.appypie.com/register?frompage=https%3A%2F%2Fconnectcloud.appypie.com%2Fconnects&lang=en",
			"login",
			"https://accounts.appypie.com/login?frompage=https%3A%2F%2Fconnectcloud.appypie.com%2Fconnects&lang=en");

	private long navigate(By clickElement, String expectedUrlPart, String stepName) {
		long start = System.currentTimeMillis();
		boolean fallbackUsed = false;
		String expectedLower = expectedUrlPart.toLowerCase();

		String fallbackUrl = fallbackUrls.get(expectedUrlPart);

		try {
			waits.waitForClickable(clickElement).click();
		} catch (Exception e) {
			// Record the exception and attempt a JS click fallback before using the configured fallback URL.
			fallbackUsed = true;
			String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
			LOG.warn("Navigation click failed for {} -> {}", stepName, errMsg);
			HealthTracker.get().addWarning(stepName, "Click failed: " + errMsg);

			// Try JavaScript click as a fallback if the element exists in the DOM
			try {
				WebElement el = driver.findElement(clickElement);
				if (el != null) {
					if (driver instanceof JavascriptExecutor) {
						((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
						fallbackUsed = false; // JS click succeeded, do not mark fallback URL used yet
						LOG.info("Performed JS click for {} as a fallback to normal click", stepName);
					}
				}
			} catch (Exception jsEx) {
				String jsErr = jsEx.getClass().getSimpleName() + ": " + jsEx.getMessage();
				LOG.warn("JS click also failed for {} -> {}", stepName, jsErr);
				HealthTracker.get().addWarning(stepName, "JS click failed: " + jsErr);

				// If JS click failed and a fallback URL is configured, navigate there
				if (fallbackUrl != null) {
					HealthTracker.get().recordFallback(stepName, fallbackUrl);
					driver.get(fallbackUrl);
				} else {
					HealthTracker.get().addWarning(stepName, "No fallback URL configured");
				}
			}
		}

		try {
			new org.openqa.selenium.support.ui.WebDriverWait(driver, Duration.ofSeconds(10))
				.until(web -> driver.getCurrentUrl().toLowerCase().contains(expectedLower));
		} catch (TimeoutException te) {
			HealthTracker.get().addWarning(stepName,
					"Navigation mismatch → URL did not contain expected fragment: " + expectedUrlPart);
		}

		long duration = System.currentTimeMillis() - start;
		HealthTracker.get().recordNavStep(stepName, duration);

		if (fallbackUsed) {
			String fallbackDetailLine = stepName + " → " + (fallbackUrl == null ? "(none)" : fallbackUrl);
			// consult HealthTracker whether this message should be suppressed from console output
			boolean suppressed = HealthTracker.get().isSuppressedConsole(fallbackDetailLine) || HealthTracker.get().isSuppressedConsole("used fallback url");
			if (!suppressed) {
				LOG.info("Used fallback URL for {} → Test continues", stepName);
			}
		}

		return duration;
	}

	public long goToFeatures() {
		return navigate(featureBtn, "features", "Features");
	}

	public long goToAppDirectory() {
		return navigate(appDirectoryBtn, "app-directory", "App Directory");
	}

	public long goToAIAutomation() {
		return navigate(aiAutomationBtn, "automation", "AI Automation");
	}

	public long goToAIConnects() {
		return navigate(aiConnectsBtn, "ai-connects", "AI Connects");
	}

	public long goToMCPServer() {
		return navigate(mcpServerBtn, "mcp-server", "MCP Server");
	}

	public long goToPricing() {
		return navigate(pricingBtn, "pricing", "Pricing");
	}

	public long goToBlog() {
		return navigate(blogBtn, "blog", "Blog");
	}

	public long goToContactSales() {
		return navigate(contactSalesBtn, "contact", "Contact Sales");
	}

	public long goToSignup() {
		return navigate(signupBtn, "signup", "Signup");
	}

	public long goToLogin() {
		return navigate(loginBtn, "login", "Login");
	}
}
