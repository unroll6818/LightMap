package dev.lightmap.core;

import java.util.Objects;

/**
 * Minimal assertions for the self-tests. The tests are plain {@code main} programs so they run
 * with nothing but a JDK: no test framework has to be downloaded to build or check the mod.
 */
public final class Check {
    private static int checks;
    private static int failures;

    private Check() {
    }

    public interface Body {
        void run() throws Exception;
    }

    public static void run(String name, Body body) {
        int before = failures;
        try {
            body.run();
        } catch (Throwable t) {
            failures++;
            System.out.println("  ! " + name + " threw " + t);
            t.printStackTrace(System.out);
        }
        System.out.println((failures == before ? "ok    " : "FAIL  ") + name);
    }

    public static void that(boolean condition, String what) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  ! expected: " + what);
        }
    }

    public static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            failures++;
            System.out.println("  ! " + what + ": expected " + expected + " but was " + actual);
        }
    }

    public static void near(double expected, double actual, double tolerance, String what) {
        checks++;
        if (!(Math.abs(expected - actual) <= tolerance)) {
            failures++;
            System.out.println("  ! " + what + ": expected " + expected + " but was " + actual);
        }
    }

    /** Prints the totals and exits non-zero if anything failed. */
    public static void finish() {
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
