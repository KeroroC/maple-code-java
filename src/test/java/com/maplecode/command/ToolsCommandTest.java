package com.maplecode.command;

import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class ToolsCommandTest {

    @Test
    void execute_listsAllTools() {
        Tool t1 = mock(Tool.class);
        when(t1.name()).thenReturn("read_file");
        when(t1.description()).thenReturn("读取文件内容");
        Tool t2 = mock(Tool.class);
        when(t2.name()).thenReturn("exec");
        when(t2.description()).thenReturn("执行命令");

        ToolRegistry registry = new ToolRegistry(List.of(t1, t2));
        CommandContext ctx = mock(CommandContext.class);

        new ToolsCommand(registry).execute("", ctx);

        verify(ctx).sendMessage(argThat(s -> s.contains("read_file")));
        verify(ctx).sendMessage(argThat(s -> s.contains("exec")));
    }
}
