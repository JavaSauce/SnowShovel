package net.javasauce.ss.tasks.report;

/**
 * Created by covers1624 on 9/1/23.
 */
public enum TestCaseState {
    BROKEN("Broken"),
    SOURCE("Source"),
    COMPILE("Compile"),
    BYTECODE_ROUND_TRIP("RoundTrip");

    public final String humanName;

    TestCaseState(String humanName) {
        this.humanName = humanName;
    }
}
