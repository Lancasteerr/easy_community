package com.febrie.backend.performance;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** 增量读已写完的 k6 请求行；保留真实起止时间，停压后也可独立复核。 */
final class BaselineJournal {
    record Event(String name, long start, long end, int status, boolean ok, long user, long article,
                 boolean liked, String version, Integer bytes, String variant, String submission, Boolean sample, String outcome) {
        boolean accepted() { return name.equals("like") && status == 202; }
        boolean viewed() { return name.equals("detail") && status == 200; }
        boolean commentSubmit() { return name.equals("comment-submit"); }
        boolean auxiliary() { return name.equals("comment-visible"); }
        // 其他场景和既有请求日志没有该字段。
        boolean sampled() { return Boolean.TRUE.equals(sample); }
    }

    private static final String MARKER = "EC_HTTP ";
    private static final String SCENARIO_START = "EC_SCENARIO_START ";
    private final Path file;
    private final ObjectMapper json;
    private final List<Event> events = new ArrayList<>();
    private long offset;
    private Long startedAt;

    BaselineJournal(Path file, ObjectMapper json) { this.file = file; this.json = json; }

    List<Event> events() { return events; }
    Long startedAt() { return startedAt; }

    void refresh() throws IOException {
        if (!Files.exists(file)) { return; }
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            long available = input.length();
            if (available <= offset) { return; }
            input.seek(available - 1);
            boolean endsWithNewline = input.read() == '\n';
            input.seek(offset);
            for (String line; (line = input.readLine()) != null;) {
                if (input.getFilePointer() > available || input.getFilePointer() == available && !endsWithNewline) { break; }
                int marker = line.indexOf(MARKER);
                if (marker >= 0) {
                    events.add(json.readValue(line.substring(marker + MARKER.length()), Event.class));
                } else if ((marker = line.indexOf(SCENARIO_START)) >= 0) {
                    startedAt = Long.parseLong(line.substring(marker + SCENARIO_START.length()).strip());
                }
                offset = input.getFilePointer();
            }
        }
    }
}
