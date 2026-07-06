package com.maplecode.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StdioTransportTest {

    private final ObjectMapper m = new ObjectMapper();
    private Path script;
    private Path tmpOut;

    @BeforeEach
    void setUp() throws IOException {
        tmpOut = Files.createTempFile("mcp-test-out-", ".log");
        script = Files.createTempFile("mcp-test-fixture-", ".sh");
        Files.writeString(script, "#!/bin/sh\nwhile IFS= read -r l; do echo \"$l\"; done\n");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(script);
        Files.deleteIfExists(tmpOut);
    }

    @Test
    void sendsAndReceivesLineDelimitedJson() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        var transport = new Stdio(List.of("/bin/sh", script.toString()),
            "[test]", tmpOut);
        transport.onInbound(received::offer);
        transport.send(m.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).get(2, TimeUnit.SECONDS);
        JsonNode back = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(back);
        assertEquals("ping", back.get("method").asText());
        transport.close(null);
    }

    @Test
    void closeKillsProcess() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        var transport = new Stdio(List.of("/bin/sh", script.toString()),
            "[closer]", tmpOut);
        transport.onInbound(received::offer);
        Thread.sleep(100);
        transport.close(null);
        assertThrows(Exception.class, () ->
            transport.send(m.readTree("{}")).get(1, TimeUnit.SECONDS));
    }
}
