package base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata annotation for test classification.
 * Used alongside TestNG {@code @Test(groups=...)} for dashboard reporting.
 *
 * <p>
 * TestNG groups control <em>execution filtering</em>.
 * This annotation controls <em>analytics and dashboard metadata</em>.
 *
 * <p>
 * Both must stay consistent — {@code BaseTest} validates this at runtime.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface TestCategory {
    /** Classification type for execution scope */
    TestType type() default TestType.REGRESSION;

    /** Whether this test requires an authenticated session */
    boolean requiresLogin() default false;

    /** Team or person responsible for this test */
    String owner() default "Unassigned";

    /** Feature area for dashboard grouping (e.g. "Homepage", "Connect Workflow") */
    String feature() default "General";

    /** Criticality of this test for product health */
    TestType.Severity severity() default TestType.Severity.MEDIUM;

    /** Default failure classification if auto-detection fails */
    TestType.FailureType defaultFailureType() default TestType.FailureType.PRODUCT_BUG;
}
