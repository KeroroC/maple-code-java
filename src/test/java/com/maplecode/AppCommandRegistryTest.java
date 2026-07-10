package com.maplecode;

import com.maplecode.command.CommandRegistry;
import com.maplecode.compact.CompactCoordinator;
import com.maplecode.memory.MemoryManager;
import com.maplecode.session.archive.SessionArchive;
import com.maplecode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AppCommandRegistryTest {
    @Test
    void productionRegistryDoesNotExposeCancel() {
        CommandRegistry registry = App.createCommandRegistry(
            new ToolRegistry(List.of()), mock(SessionArchive.class),
            mock(CompactCoordinator.class), mock(MemoryManager.class));
        assertTrue(registry.lookup("help").isPresent());
        assertTrue(registry.lookup("exit").isPresent());
        assertTrue(registry.lookup("cancel").isEmpty());
        assertFalse(registry.completableNames().contains("cancel"));
    }
}
