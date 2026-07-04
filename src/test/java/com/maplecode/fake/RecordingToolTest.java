package com.maplecode.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RecordingToolTest {

    @Test
    void recordsAllExecutions() {
        var tool = new RecordingTool("read_file", ToolResult.ok("hello"));
        var ctx = new ToolContext(Path.of("/tmp"), 1024, 30, 100, 100);
        tool.execute(null, ctx);
        tool.execute(null, ctx);
        assertEquals(2, tool.calls().size());
    }

    @Test
    void returnsPresetResult() {
        var tool = new RecordingTool("exec", ToolResult.error("boom"));
        var ctx = new ToolContext(Path.of("/tmp"), 1024, 30, 100, 100);
        var r = tool.execute(null, ctx);
        assertTrue(r.isError());
        assertEquals("boom", r.content());
    }
}
