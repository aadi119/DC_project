/**
 * Small helper for the structured terminal output of Experiments 5-7.
 */
public final class Terminal {

    public static final String HEAVY_RULE = "=".repeat(58);
    public static final String LIGHT_RULE = "-".repeat(58);

    private Terminal() {
    }

    /** Major heading, e.g. the start of an experiment. */
    public static void banner(String... lines) {
        System.out.println();
        System.out.println(HEAVY_RULE);
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println(HEAVY_RULE);
    }

    /** Sub-heading inside an experiment. */
    public static void section(String title) {
        System.out.println();
        System.out.println(LIGHT_RULE);
        System.out.println(title);
        System.out.println(LIGHT_RULE);
    }

    public static void line(String text) {
        System.out.println(text);
    }

    public static void blank() {
        System.out.println();
    }
}
