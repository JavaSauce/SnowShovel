package net.javasauce.ss.util;

/**
 * Created by covers1624 on 8/22/25.
 */
public record CommittedTestCasePair(
        String id,
        CommittedTestCaseDef before,
        CommittedTestCaseDef now
) { }
