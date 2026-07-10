package io.configd.linz.schedule;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deterministic serializer for a {@link Schedule}. The output is a stable function
 * of the schedule (ordered lists, fixed formatting), so two runs of the same seed
 * yield a byte-identical file - the reproducibility proof
 * ({@code diff schedule-<seed>.json}).
 */
public final class ScheduleJson {

    private ScheduleJson() {}

    public static void write(Schedule s, Path out) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"seed\": ").append(s.seed).append(",\n");
        sb.append("  \"nodes\": ").append(s.nodes).append(",\n");
        sb.append("  \"clients\": ").append(s.clients).append(",\n");
        sb.append("  \"keys\": ").append(s.keys).append(",\n");
        sb.append("  \"durationMs\": ").append(s.durationMs).append(",\n");
        sb.append("  \"mode\": \"").append(s.mode).append("\",\n");

        sb.append("  \"faults\": [");
        for (int i = 0; i < s.faults.size(); i++) {
            Schedule.FaultEvent f = s.faults.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"offsetMs\": ").append(f.offsetMs())
              .append(", \"kind\": \"").append(f.kind())
              .append("\", \"nodeId\": ").append(f.nodeId())
              .append(", \"durationMs\": ").append(f.durationMs())
              .append(", \"param\": ").append(f.param()).append("}");
        }
        sb.append(s.faults.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"workload\": [");
        for (int c = 0; c < s.workload.size(); c++) {
            sb.append(c == 0 ? "\n" : ",\n");
            sb.append("    [");
            var ops = s.workload.get(c);
            for (int j = 0; j < ops.size(); j++) {
                Schedule.WorkOp op = ops.get(j);
                sb.append(j == 0 ? "\n" : ",\n");
                sb.append("      {\"offsetMs\": ").append(op.offsetMs())
                  .append(", \"kind\": \"").append(op.kind())
                  .append("\", \"keyIndex\": ").append(op.keyIndex())
                  .append(", \"token\": \"").append(op.token()).append("\"}");
            }
            sb.append(ops.isEmpty() ? "]" : "\n    ]");
        }
        sb.append(s.workload.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");

        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }
}
