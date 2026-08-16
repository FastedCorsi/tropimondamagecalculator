package fr.tropimon.damagecalc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

final class TypeIconRenderer {
    private static final Identifier ATLAS = Identifier.of(TropimonDamageCalcClient.MOD_ID,
            "textures/gui/type_icons.png");
    private static final int ICON_SIZE = 60;
    private static final int COLUMNS = 5;
    private static final int ATLAS_WIDTH = COLUMNS * ICON_SIZE;
    private static final int ATLAS_HEIGHT = 4 * ICON_SIZE;

    private TypeIconRenderer() {
    }

    static void draw(DrawContext context, PokeType type, int x, int y, int size) {
        if (type == PokeType.NONE) {
            return;
        }
        int index = type.ordinal();
        float u = (index % COLUMNS) * ICON_SIZE;
        float v = (index / COLUMNS) * ICON_SIZE;
        context.drawTexture(ATLAS, x, y, size, size, u, v, ICON_SIZE, ICON_SIZE, ATLAS_WIDTH, ATLAS_HEIGHT);
    }
}
