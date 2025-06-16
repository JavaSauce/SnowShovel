package net.javasauce.ss.tasks.report;

import java.util.List;

/**
 * Created by covers1624 on 9/1/23.
 */
public enum TestCaseState {
    BROKEN("Broken"),
    SOURCE("Source"),
    COMPILE("Compile"),
    BYTECODE_ROUND_TRIP("RoundTrip");

    public static final List<TestCaseState> VALUES = List.of(values());

    public final String humanName;

    TestCaseState(String humanName) {
        this.humanName = humanName;
    }
}
