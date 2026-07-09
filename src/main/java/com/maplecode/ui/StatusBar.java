package com.maplecode.ui;

import com.maplecode.provider.TokenUsage;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.List;

/**
 * 终端底部状态栏，显示模型名、token 用量、模式和工作目录。
 * 依赖 JLine 的 {@link Status}（需要终端支持 scroll region）。
 */
public class StatusBar {

    /** 状态栏显示数据。 */
    public record StatusState(
        String model,
        TokenUsage usage,
        String mode,
        String workingDir
    ) {}

    private final Status status;
    private final boolean supported;
    private StatusState lastState;

    public StatusBar(Terminal terminal) {
        this.status = Status.getStatus(terminal);
        this.supported = status != null;
    }

    /** 更新状态栏显示。终端不支持时为 no-op。 */
    public void update(StatusState state) {
        if (!supported) return;
        this.lastState = state;
        status.update(List.of(render(state)));
    }

    /** 终端尺寸变化后重新渲染。终端不支持时为 no-op。 */
    public void resize() {
        if (!supported) return;
        status.resize();
        if (lastState != null) {
            status.update(List.of(render(lastState)));
        }
    }

    /** 终端是否支持状态栏。 */
    public boolean isSupported() {
        return supported;
    }

    // --- package-private static methods for testing ---

    static AttributedString render(StatusState state) {
        var sb = new AttributedStringBuilder();
        sb.styled(AttributedStyle.BOLD, state.model());
        sb.append(" │ ");
        sb.append(formatUsage(state.usage()));
        sb.append(" │ ");
        sb.append(coloredMode(state.mode()));
        sb.append(" │ ");
        sb.styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN), state.workingDir());
        return sb.toAttributedString();
    }

    static String formatUsage(TokenUsage usage) {
        if (usage == null) return "tok:-/-";
        return "tok:" + abbreviate(usage.inputTokens()) + "/" + abbreviate(usage.outputTokens());
    }

    static String abbreviate(int n) {
        if (n < 1000) return Integer.toString(n);
        if (n < 10000) return String.format("%.1fk", n / 1000.0);
        return (n / 1000) + "k";
    }

    static AttributedString coloredMode(String mode) {
        // 支持复合模式如 "plan:strict" — 取最高优先级的颜色
        AttributedStyle style;
        if (mode.contains("strict")) {
            style = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
        } else if (mode.contains("permissive")) {
            style = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
        } else if (mode.contains("plan")) {
            style = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
        } else {
            style = AttributedStyle.DEFAULT;
        }
        return new AttributedString(mode, style);
    }
}
