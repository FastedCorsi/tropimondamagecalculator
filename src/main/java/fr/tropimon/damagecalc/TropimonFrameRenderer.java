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
    private static final int COMPACT_HORIZONTAL = 5;
    private static final int COMPACT_VERTICAL = 4;
    private static final int BACKGROUND = 0x9810171C;
    private static Boolean textureAvailable;

    private TropimonFrameRenderer() {
    }

    static void draw(DrawContext context, int x, int y, int width, int height) {
        if (width < LEFT + RIGHT || height < TOP + BOTTOM) {
            return;
        }
        if (!textureAvailable()) {
            context.fill(x + 4, y + 4, x + width - 4, y + height - 4, BACKGROUND);
            CobblemonPanelRenderer.drawBorder(context, x, y, width, height);
            return;
        }

        drawFrame(context, x, y, width, height, LEFT, RIGHT, TOP, BOTTOM, true);
    }

    static void drawCompact(DrawContext context, int x, int y, int width, int height) {
        if (width < COMPACT_HORIZONTAL * 2 || height < COMPACT_VERTICAL * 2) {
            return;
        }
        if (!textureAvailable()) {
            CobblemonPanelRenderer.drawBorder(context, x, y, width, height);
            return;
        }
        drawFrame(context, x, y, width, height,
                COMPACT_HORIZONTAL, COMPACT_HORIZONTAL,
                COMPACT_VERTICAL, COMPACT_VERTICAL, false);
    }

    static void clearCache() {
        textureAvailable = null;
    }

    private static void drawFrame(DrawContext context, int x, int y, int width, int height,
                                  int left, int right, int top, int bottom,
                                  boolean fillBackground) {
        int innerWidth = width - left - right;
        int innerHeight = height - top - bottom;
        int sourceInnerWidth = TEXTURE_WIDTH - LEFT - RIGHT;
        int sourceInnerHeight = TEXTURE_HEIGHT - TOP - BOTTOM;

        if (fillBackground) {
            context.fill(x + left, y + top, x + width - right, y + height - bottom, BACKGROUND);
        }

        drawPart(context, x + left, y, innerWidth, top,
                LEFT, 0, sourceInnerWidth, TOP);
        drawPart(context, x + left, y + height - bottom, innerWidth, bottom,
                LEFT, TEXTURE_HEIGHT - BOTTOM, sourceInnerWidth, BOTTOM);
        drawPart(context, x, y + top, left, innerHeight,
                0, TOP, LEFT, sourceInnerHeight);
        drawPart(context, x + width - right, y + top, right, innerHeight,
                TEXTURE_WIDTH - RIGHT, TOP, RIGHT, sourceInnerHeight);

        drawPart(context, x, y, left, top, 0, 0, LEFT, TOP);
        drawPart(context, x + width - right, y, right, top,
                TEXTURE_WIDTH - RIGHT, 0, RIGHT, TOP);
        drawPart(context, x, y + height - bottom, left, bottom,
                0, TEXTURE_HEIGHT - BOTTOM, LEFT, BOTTOM);
        drawPart(context, x + width - right, y + height - bottom, right, bottom,
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
