package com.maplecode.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDesc(
    String name,
    String description,
    JsonNode inputSchema
) {}
