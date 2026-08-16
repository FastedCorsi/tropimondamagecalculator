package fr.tropimon.damagecalc;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

final class CobblemonPokemonProfileRenderer {
    private static final int MAX_CACHED_PROFILES = 96;
    private static final Map<String, ProfileEntry> PROFILE_CACHE = new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ProfileEntry> eldest) {
            return size() > MAX_CACHED_PROFILES;
        }
    };
    private static final LinkedHashSet<String> UNRENDERABLE_KEYS = new LinkedHashSet<>();
    private static Reflection reflection;
    private static boolean unavailable;

    private CobblemonPokemonProfileRenderer() {
    }

    static boolean draw(DrawContext context, SpeciesData species, int x, int y, int size) {
        return draw(context, species, x, y, size, 0.0F, "static");
    }

    static boolean drawAnimated(DrawContext context, SpeciesData species, int x, int y, int size,
                                float frameDelta, String animationSlot) {
        // GUI tick deltas can stay at zero while a non-pausing battle screen is layered underneath.
        // The profile entry computes its real elapsed time, so a positive marker keeps the idle pose advancing.
        return draw(context, species, x, y, size, 1.0F, animationSlot);
    }

    private static boolean draw(DrawContext context, SpeciesData species, int x, int y, int size,
                                float frameDelta, String animationSlot) {
        if (unavailable || species == null) {
            return false;
        }
        boolean scissorEnabled = false;
        boolean matrixPushed = false;
        try {
            Reflection api = reflection();
            ProfileEntry profile = profile(api, species, animationSlot);
            if (profile == null) {
                return false;
            }

            context.enableScissor(x, y, x + size, y + size);
            scissorEnabled = true;
            context.getMatrices().push();
            matrixPushed = true;
            float scale = size < 32 ? Math.max(0.45F, size / 38.0F) : Math.max(1.6F, size / 34.0F);
            float yOffset = size < 32 ? -4.0F : -12.0F;
            context.getMatrices().translate(x + size / 2.0, y + yOffset, 1000.0);
            context.getMatrices().scale(scale, scale, scale);

            Quaternionf rotation = new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(13.0F),
                    (float) Math.toRadians(25.0F),
                    0.0F
            );
            api.drawProfileDefault.invoke(null,
                    profile.renderable,
                    context.getMatrices(),
                    rotation,
                    api.renderPose,
                    profile.state,
                    profile.animationDelta(frameDelta > 0.0F),
                    20.0F,
                    true,
                    false,
                    1.0F,
                    1.0F,
                    1.0F,
                    1.0F,
                    0.0F,
                    0.0F,
                    32704,
                    null
            );
            return true;
        } catch (Throwable throwable) {
            TropimonDamageCalcClient.warnOnce("pokemon-profile-render", "Impossible d'afficher les modeles Pokemon Cobblemon", throwable);
            return false;
        } finally {
            if (matrixPushed) {
                try {
                    context.getMatrices().pop();
                } catch (Throwable ignored) {
                }
            }
            if (scissorEnabled) {
                try {
                    context.disableScissor();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static ProfileEntry profile(Reflection api, SpeciesData species, String animationSlot) {
        String cacheKey = animationSlot + "|" + renderKey(species);
        synchronized (PROFILE_CACHE) {
            ProfileEntry cached = PROFILE_CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            if (UNRENDERABLE_KEYS.contains(cacheKey)) {
                return null;
            }
        }
        try {
            Object cobblemonSpecies = api.getSpeciesByName.invoke(null, species.cobblemonSpeciesId());
            if (cobblemonSpecies == null) {
                cobblemonSpecies = api.getSpeciesByIdentifier.invoke(null, Identifier.of("cobblemon", species.cobblemonSpeciesId()));
            }
            if (cobblemonSpecies == null) {
                markUnrenderable(cacheKey);
                return null;
            }
            Object renderable = api.renderableConstructor.newInstance(cobblemonSpecies, new HashSet<>(species.aspects()), ItemStack.EMPTY);
            Object state = newState(api);
            if (state == null) {
                markUnrenderable(cacheKey);
                return null;
            }
            ProfileEntry created = new ProfileEntry(renderable, state);
            synchronized (PROFILE_CACHE) {
                PROFILE_CACHE.put(cacheKey, created);
            }
            return created;
        } catch (Throwable throwable) {
            markUnrenderable(cacheKey);
            TropimonDamageCalcClient.warnOnce("pokemon-profile-" + renderKey(species),
                    "Modele Cobblemon inutilisable pour " + species.name(), throwable);
            return null;
        }
    }

    private static String renderKey(SpeciesData species) {
        return species.cobblemonSpeciesId() + "|" + String.join(",", species.aspects());
    }

    private static void markUnrenderable(String key) {
        synchronized (PROFILE_CACHE) {
            if (UNRENDERABLE_KEYS.size() >= MAX_CACHED_PROFILES) {
                String oldest = UNRENDERABLE_KEYS.getFirst();
                UNRENDERABLE_KEYS.remove(oldest);
            }
            UNRENDERABLE_KEYS.add(key);
        }
    }

    static void clearCaches() {
        synchronized (PROFILE_CACHE) {
            PROFILE_CACHE.clear();
            UNRENDERABLE_KEYS.clear();
        }
        unavailable = false;
    }

    static String diagnosticSummary() {
        synchronized (PROFILE_CACHE) {
            return "profiles=" + PROFILE_CACHE.size() + "/" + MAX_CACHED_PROFILES
                    + ", unavailable=" + unavailable + ", rejected=" + UNRENDERABLE_KEYS.size();
        }
    }

    private static Object newState(Reflection api) {
        try {
            return api.floatingStateConstructor.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Reflection reflection() throws ReflectiveOperationException {
        if (reflection != null) {
            return reflection;
        }
        try {
            Class<?> speciesRegistry = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            Class<?> speciesClass = Class.forName("com.cobblemon.mod.common.pokemon.Species");
            Class<?> renderableClass = Class.forName("com.cobblemon.mod.common.pokemon.RenderablePokemon");
            Class<?> floatingStateClass = Class.forName("com.cobblemon.mod.common.client.render.models.blockbench.FloatingState");
            Class<?> poseTypeClass = Class.forName("com.cobblemon.mod.common.entity.PoseType");
            Class<?> guiUtilsClass = Class.forName("com.cobblemon.mod.common.client.gui.PokemonGuiUtilsKt");

            Method drawProfileDefault = null;
            for (Method method : guiUtilsClass.getMethods()) {
                if (!method.getName().equals("drawProfilePokemon$default")) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 17 && parameters[0].equals(renderableClass)) {
                    drawProfileDefault = method;
                    break;
                }
            }
            if (drawProfileDefault == null) {
                throw new NoSuchMethodException("drawProfilePokemon$default(RenderablePokemon, ...)");
            }

            Constructor<?> renderableConstructor = renderableClass.getConstructor(speciesClass, java.util.Set.class, ItemStack.EMPTY.getClass());
            Constructor<?> stateConstructor = floatingStateClass.getConstructor();
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object renderPose = Enum.valueOf((Class<? extends Enum>) poseTypeClass.asSubclass(Enum.class), "PROFILE");

            reflection = new Reflection(
                    speciesRegistry.getMethod("getByName", String.class),
                    speciesRegistry.getMethod("getByIdentifier", Identifier.class),
                    renderableConstructor,
                    stateConstructor,
                    drawProfileDefault,
                    renderPose
            );
            return reflection;
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            throw exception;
        }
    }

    private record Reflection(
            Method getSpeciesByName,
            Method getSpeciesByIdentifier,
            Constructor<?> renderableConstructor,
            Constructor<?> floatingStateConstructor,
            Method drawProfileDefault,
            Object renderPose
    ) {
    }

    private static final class ProfileEntry {
        private final Object renderable;
        private final Object state;
        private long lastAnimatedFrameNanos;

        private ProfileEntry(Object renderable, Object state) {
            this.renderable = renderable;
            this.state = state;
        }

        private float animationDelta(boolean animated) {
            if (!animated) {
                return 0.0F;
            }
            long now = System.nanoTime();
            if (lastAnimatedFrameNanos == 0L) {
                lastAnimatedFrameNanos = now;
                return 0.0F;
            }
            long elapsed = now - lastAnimatedFrameNanos;
            lastAnimatedFrameNanos = now;
            return Math.max(0.0F, Math.min(1.0F, elapsed / 50_000_000.0F));
        }
    }
}
