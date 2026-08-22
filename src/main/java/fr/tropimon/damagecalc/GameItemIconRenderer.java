package fr.tropimon.damagecalc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

final class GameItemIconRenderer {
    private static final Map<String, ItemStack> ITEMS = new LinkedHashMap<>();
    private static boolean loaded;

    private GameItemIconRenderer() {
    }

    static boolean draw(DrawContext context, String itemName, int x, int y) {
        ItemStack stack = find(itemName);
        if (stack.isEmpty()) {
            return false;
        }
        context.drawItem(stack, x, y);
        return true;
    }

    static synchronized void clearCaches() {
        ITEMS.clear();
        loaded = false;
    }

    private static synchronized ItemStack find(String query) {
        load();
        return ITEMS.getOrDefault(TropimonDex.normalize(query), ItemStack.EMPTY);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (Item item : Registries.ITEM) {
            ItemStack stack = item.getDefaultStack();
            if (stack.isEmpty()) {
                continue;
            }
            Identifier id = Registries.ITEM.getId(item);
            ITEMS.putIfAbsent(TropimonDex.normalize(id.toString()), stack);
            ITEMS.putIfAbsent(TropimonDex.normalize(id.getPath()), stack);
            ITEMS.putIfAbsent(TropimonDex.normalize(stack.getName().getString()), stack);
        }
    }
}
