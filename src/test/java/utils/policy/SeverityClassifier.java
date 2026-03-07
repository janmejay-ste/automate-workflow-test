package utils.policy;

import base.TestCategory;
import base.TestType;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;

/**
 * Logic to determine FailureType based on exception and annotation.
 */
public class SeverityClassifier {

    public static TestType.FailureType classify(Throwable t, TestCategory metadata) {
        if (t == null)
            return TestType.FailureType.PRODUCT_BUG;

        // Rule-based classification for unambiguous cases
        if (t instanceof TimeoutException)
            return TestType.FailureType.ENVIRONMENT;
        if (t instanceof WebDriverException && t.getMessage() != null && t.getMessage().contains("session")) {
            return TestType.FailureType.ENVIRONMENT;
        }
        if (t instanceof AssertionError)
            return TestType.FailureType.TEST_ASSERTION;

        // Fallback to explicit metadata or general default
        if (metadata != null) {
            return metadata.defaultFailureType();
        }

        return TestType.FailureType.PRODUCT_BUG;
    }
}
