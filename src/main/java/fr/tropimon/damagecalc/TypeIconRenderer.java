package fr.tropimon.damagecalc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

final class TypeIconRenderer {
    private static final Identifier ATLAS = Identifier.of("cobblemon", "textures/gui/types_small.png");
    private static final int ICON_SIZE = 18;
    private static final int ATLAS_WIDTH = 18 * ICON_SIZE;
    private static final int ATLAS_HEIGHT = ICON_SIZE;

    private TypeIconRenderer() {
    }

    static void draw(DrawContext context, PokeType type, int x, int y, int size) {
        if (type == PokeType.NONE) {
            return;
        }
        int index = atlasIndex(type);
        context.drawTexture(ATLAS, x, y, size, size, index * ICON_SIZE, 0,
                ICON_SIZE, ICON_SIZE, ATLAS_WIDTH, ATLAS_HEIGHT);
    }

    static int atlasIndex(PokeType type) {
        return switch (type) {
            case NORMAL -> 0;
            case FIRE -> 1;
            case WATER -> 2;
            case GRASS -> 3;
            case ELECTRIC -> 4;
            case ICE -> 5;
            case FIGHTING -> 6;
            case POISON -> 7;
            case GROUND -> 8;
            case FLYING -> 9;
            case PSYCHIC -> 10;
            case BUG -> 11;
            case ROCK -> 12;
            case GHOST -> 13;
            case DRAGON -> 14;
            case DARK -> 15;
            case STEEL -> 16;
            case FAIRY -> 17;
            case NONE -> -1;
        };
    }
}
