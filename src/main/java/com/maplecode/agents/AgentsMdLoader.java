package com.maplecode.agents;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

public final class AgentsMdLoader {

    private AgentsMdLoader() {}

    /**
     * 加载 3 层 AGENTS.md 并拼接。
     *
     * @param cwd 项目根（最高优先级）
     * @param userHome 用户主目录（最低优先级，从 userHome/.maplecode/AGENTS.md 读）
     * @return 拼接后的内容；任何层缺失 / 失败都被静默跳过
     */
    public static String load(Path cwd, Path userHome) {
        // 三层占位 Layer（exists=false, content=""）
        List<Layer> placeholders = List.of(
            Layer.empty(cwd.resolve("AGENTS.md")),                          // 1 项目根（最高）
            Layer.empty(cwd.resolve(".maplecode/AGENTS.md")),               // 2 项目 .maplecode
            Layer.empty(userHome.resolve(".maplecode/AGENTS.md"))           // 3 用户全局（最低）
        );
        // 读取
        List<Layer> populated = placeholders.stream()
            .map(LayerReader::read)
            .toList();
        // 解析 include
        List<String> expanded = populated.stream()
            .filter(Layer::exists)
            .map(layer -> IncludeResolver.resolve(
                layer.content(),
                layer.absolutePath().getParent(),
                new HashSet<>(),
                0,
                IncludeLimits.defaults()))
            .toList();
        // 拼接
        return Concatenator.join(expanded);
    }
}
