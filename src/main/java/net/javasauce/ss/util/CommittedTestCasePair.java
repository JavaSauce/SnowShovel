package net.javasauce.ss.util;

import org.jetbrains.annotations.Nullable;

/**
 * Created by covers1624 on 8/22/25.
 */
public record CommittedTestCasePair(
        String id,
        @Nullable CommittedTestCaseDef before,
        CommittedTestCaseDef now
) { }
