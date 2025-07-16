package net.javasauce.ss.util;

import net.javasauce.ss.tasks.report.TestCaseDef;

/**
 * Created by covers1624 on 6/18/25.
 */
public record CommittedTestCaseDef(String commit, String commitTitle, TestCaseDef def) {
}
