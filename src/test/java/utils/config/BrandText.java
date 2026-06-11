package utils.config;

/**
 * Centralised brand-text constants for the test suite.
 *
 * <p>Created as part of the {@code Appy Pie Automate → Flozic} rebrand migration.
 * Any test assertion, log message, or generated artifact that references the product
 * name by string literal should use {@link #PRODUCT_NAME} so the rebrand is a
 * single-point config change.</p>
 *
 * <p>Override via {@code -Dbrand.productName=...} for cross-brand smoke runs.</p>
 */
public final class BrandText {

    /** Current product name. */
    public static final String PRODUCT_NAME = System.getProperty(
            "brand.productName", "Flozic");

    /** Pre-rebrand product name, retained for legacy-redirect verification tests. */
    public static final String PRODUCT_NAME_LEGACY = "Appy Pie Automate";

    private BrandText() {}
}
