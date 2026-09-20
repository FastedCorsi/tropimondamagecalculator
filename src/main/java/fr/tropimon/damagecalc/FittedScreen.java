package fr.tropimon.damagecalc;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.joml.Vector2i;

import java.util.ArrayList;
import java.util.List;

/** Local panel fitting: rendering, input and clipping use the same coordinates. */
abstract class FittedScreen extends Screen {
    private final int minimumWidth;
    private final int minimumHeight;
    private ScreenFit fit;

    protected FittedScreen(Text title, int minimumWidth, int minimumHeight) {
        super(title);
        this.minimumWidth = minimumWidth;
        this.minimumHeight = minimumHeight;
    }

    @Override protected final void init() {
        // clearAndInit also calls init, when width/height already describe the panel.
        fit = ScreenFit.of(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(),
                minimumWidth, minimumHeight);
        width = fit.width();
        height = fit.height();
        initContent();
    }

    protected abstract void initContent();
    protected abstract void renderContent(DrawContext context, int mouseX, int mouseY, float delta);

    @Override public final void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelWidth = width, panelHeight = height;
        width = fit.viewportWidth();
        height = fit.viewportHeight();
        try {
            super.renderBackground(context, mouseX, mouseY, delta);
        } finally {
            width = panelWidth;
            height = panelHeight;
        }
        context.getMatrices().push();
        try {
            context.getMatrices().scale(fit.scale(), fit.scale(), 1F);
            renderContent(context, (int) Math.floor(fit.local(mouseX)),
                    (int) Math.floor(fit.local(mouseY)), delta);
        } finally {
            context.getMatrices().pop();
        }
    }

    protected final void renderWidgets(DrawContext context, int mouseX, int mouseY, float delta) {
        for (var child : children()) {
            if (child instanceof Drawable drawable) drawable.render(context, mouseX, mouseY, delta);
        }
    }

    protected static void scissor(DrawContext context, int left, int top, int right, int bottom) {
        var matrix = context.getMatrices().peek().getPositionMatrix();
        var start = matrix.transformPosition(new org.joml.Vector3f(left, top, 0));
        var end = matrix.transformPosition(new org.joml.Vector3f(right, bottom, 0));
        context.enableScissor((int) Math.floor(start.x), (int) Math.floor(start.y),
                (int) Math.ceil(end.x), (int) Math.ceil(end.y));
    }

    // Tooltips are drawn by Screen.renderWithTooltip after our matrix is restored.
    // Keep their normal font and clamp them to the real viewport, including keyboard focus.
    @Override public void setTooltip(List<OrderedText> lines, TooltipPositioner positioner, boolean focused) {
        super.setTooltip(lines, (sw, sh, mx, my, tw, th) -> {
            var position = positioner.getPosition(width, height,
                    (int) fit.local(mx), (int) fit.local(my),
                    (int) Math.ceil(tw / fit.scale()), (int) Math.ceil(th / fit.scale()));
            return new Vector2i(Math.max(4, Math.min(sw - tw - 4, fit.pixel(position.x()))),
                    Math.max(4, Math.min(sh - th - 4, fit.pixel(position.y()))));
        }, focused);
    }

    protected final void queueTooltip(TextRenderer renderer, Text text, int mouseX, int mouseY) {
        queueTooltip(renderer, List.of(text), mouseX, mouseY);
    }

    protected final void queueTooltip(TextRenderer renderer, List<Text> lines, int mouseX, int mouseY) {
        List<OrderedText> wrapped = new ArrayList<>();
        for (Text line : lines) wrapped.addAll(renderer.wrapLines(line, Math.min(300, fit.viewportWidth() - 24)));
        setTooltip(wrapped);
    }

    @Override public final boolean mouseClicked(double x, double y, int button) {
        return clickContent(fit.local(x), fit.local(y), button);
    }
    protected boolean clickContent(double x, double y, int button) { return super.mouseClicked(x, y, button); }

    @Override public final boolean mouseReleased(double x, double y, int button) {
        return releaseContent(fit.local(x), fit.local(y), button);
    }
    protected boolean releaseContent(double x, double y, int button) { return super.mouseReleased(x, y, button); }

    @Override public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return dragContent(fit.local(x), fit.local(y), button, fit.local(dx), fit.local(dy));
    }
    protected boolean dragContent(double x, double y, int button, double dx, double dy) {
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return scrollContent(fit.local(x), fit.local(y), horizontal, vertical);
    }
    protected boolean scrollContent(double x, double y, double horizontal, double vertical) {
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public void mouseMoved(double x, double y) { super.mouseMoved(fit.local(x), fit.local(y)); }
}
