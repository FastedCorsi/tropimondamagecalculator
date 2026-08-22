package fr.tropimon.damagecalc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Draws only the frame from Tropimon's navigator panel, keeping the world visible inside. */
final class TropimonFrameRenderer {
    private static final Identifier TEXTURE = Identifier.of(
            "tropimodclient", "guis/navigator/navmain/navigator.png");
    private static final int TEXTURE_WIDTH = 345;
    private static final int TEXTURE_HEIGHT = 205;
    private static final int LEFT = 16;
    private static final int RIGHT = 16;
    private static final int TOP = 14;
    private static final int BOTTOM = 12;
    private static final int BACKGROUND = 0x9810171C;
    private static Boolean textureAvailable;

    private TropimonFrameRenderer() {
    }

    static void draw(DrawContext context, int x, int y, int width, int height) {
        if (width < LEFT + RIGHT || height < TOP + BOTTOM) {
            return;
        }
        if (!textureAvailable()) {
            CobblemonPanelRenderer.drawBorder(context, x, y, width, height);
            return;
        }

        int innerWidth = width - LEFT - RIGHT;
        int innerHeight = height - TOP - BOTTOM;
        int sourceInnerWidth = TEXTURE_WIDTH - LEFT - RIGHT;
        int sourceInnerHeight = TEXTURE_HEIGHT - TOP - BOTTOM;

        context.fill(x + LEFT, y + TOP, x + width - RIGHT, y + height - BOTTOM, BACKGROUND);

        drawPart(context, x + LEFT, y, innerWidth, TOP,
                LEFT, 0, sourceInnerWidth, TOP);
        drawPart(context, x + LEFT, y + height - BOTTOM, innerWidth, BOTTOM,
                LEFT, TEXTURE_HEIGHT - BOTTOM, sourceInnerWidth, BOTTOM);
        drawPart(context, x, y + TOP, LEFT, innerHeight,
                0, TOP, LEFT, sourceInnerHeight);
        drawPart(context, x + width - RIGHT, y + TOP, RIGHT, innerHeight,
                TEXTURE_WIDTH - RIGHT, TOP, RIGHT, sourceInnerHeight);

        drawPart(context, x, y, LEFT, TOP, 0, 0, LEFT, TOP);
        drawPart(context, x + width - RIGHT, y, RIGHT, TOP,
                TEXTURE_WIDTH - RIGHT, 0, RIGHT, TOP);
        drawPart(context, x, y + height - BOTTOM, LEFT, BOTTOM,
                0, TEXTURE_HEIGHT - BOTTOM, LEFT, BOTTOM);
        drawPart(context, x + width - RIGHT, y + height - BOTTOM, RIGHT, BOTTOM,
                TEXTURE_WIDTH - RIGHT, TEXTURE_HEIGHT - BOTTOM, RIGHT, BOTTOM);
    }

    private static void drawPart(DrawContext context, int x, int y, int width, int height,
                                 int sourceX, int sourceY, int sourceWidth, int sourceHeight) {
        context.drawTexture(TEXTURE, x, y, width, height, sourceX, sourceY,
                sourceWidth, sourceHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    private static boolean textureAvailable() {
        if (textureAvailable == null) {
            textureAvailable = MinecraftClient.getInstance().getResourceManager()
                    .getResource(TEXTURE).isPresent();
        }
        return textureAvailable;
    }
}
