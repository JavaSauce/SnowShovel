package net.javasauce.ss.util;

import java.util.List;

/**
 * Created by covers1624 on 7/22/25.
 */
public record RunRequest(
        String reason,
        String decompilerVersion,
        List<VersionRequest> versions
) { }
