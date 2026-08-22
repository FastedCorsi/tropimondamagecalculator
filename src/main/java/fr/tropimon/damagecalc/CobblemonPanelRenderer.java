package fr.tropimon.damagecalc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

final class CobblemonPanelRenderer {
    private static final Identifier TEXTURE = Identifier.of(
            "cobblemon", "textures/gui/battle/battle_info_underlay.png");
    private static final int TEXTURE_SIZE = 28;
    private static final int BORDER = 4;
    private static final int CENTER = TEXTURE_SIZE - BORDER * 2;

    private CobblemonPanelRenderer() {
    }

    static void draw(DrawContext context, int x, int y, int width, int height) {
        if (width < BORDER * 2 || height < BORDER * 2) {
            return;
        }
        int innerWidth = width - BORDER * 2;
        int innerHeight = height - BORDER * 2;

        drawPart(context, x + BORDER, y + BORDER, innerWidth, innerHeight,
                BORDER, BORDER, CENTER, CENTER);
        drawPart(context, x + BORDER, y, innerWidth, BORDER,
                BORDER, 0, CENTER, BORDER);
        drawPart(context, x + BORDER, y + height - BORDER, innerWidth, BORDER,
                BORDER, TEXTURE_SIZE - BORDER, CENTER, BORDER);
        drawPart(context, x, y + BORDER, BORDER, innerHeight,
                0, BORDER, BORDER, CENTER);
        drawPart(context, x + width - BORDER, y + BORDER, BORDER, innerHeight,
                TEXTURE_SIZE - BORDER, BORDER, BORDER, CENTER);

        drawPart(context, x, y, BORDER, BORDER, 0, 0, BORDER, BORDER);
        drawPart(context, x + width - BORDER, y, BORDER, BORDER,
                TEXTURE_SIZE - BORDER, 0, BORDER, BORDER);
        drawPart(context, x, y + height - BORDER, BORDER, BORDER,
                0, TEXTURE_SIZE - BORDER, BORDER, BORDER);
        drawPart(context, x + width - BORDER, y + height - BORDER, BORDER, BORDER,
                TEXTURE_SIZE - BORDER, TEXTURE_SIZE - BORDER, BORDER, BORDER);
    }

    private static void drawPart(DrawContext context, int x, int y, int width, int height,
                                 int sourceX, int sourceY, int sourceWidth, int sourceHeight) {
        context.drawTexture(TEXTURE, x, y, width, height, sourceX, sourceY,
                sourceWidth, sourceHeight, TEXTURE_SIZE, TEXTURE_SIZE);
    }
}
