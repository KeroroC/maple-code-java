package com.maplecode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

public record PermissionRequest(String toolName, JsonNode args, Path cwd) {}
