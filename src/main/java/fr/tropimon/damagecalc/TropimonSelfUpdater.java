package fr.tropimon.damagecalc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.*;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;

/** Autonomous opt-in updater; no dependency on another Tropimon mod. */
final class TropimonSelfUpdater {
    private static final String MOD_ID = "tropimon_damage_calc";
    private static final String REPOSITORY = "tropimondamagecalculator";
    private static final String RELEASE_API = "https://api.github.com/repos/FastedCorsi/" + REPOSITORY + "/releases?per_page=20";
    private static final String CONSENT_RELEASE = "<!-- tropimon-consent-updater:2 -->";
    private static final String RELEASE_DOWNLOAD_PREFIX = "https://github.com/FastedCorsi/" + REPOSITORY + "/releases/download/";
    private static final Duration CHECK_INTERVAL = Duration.ofHours(6);
    private static final long MAX_JAR_SIZE = 64L * 1024 * 1024;
    private static HttpClient http;
    private static synchronized HttpClient http() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return http;
    }
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile ReleaseOffer pending;
    private static volatile boolean checksAllowed;
    private static boolean asked, shown;
    private static Logger log;

    private TropimonSelfUpdater() {}

    static void start(Logger logger) {
        log = logger;
        if (FabricLoader.getInstance().isDevelopmentEnvironment() || Boolean.getBoolean("tropimon.smoke")) return;
        try {
            JsonObject config = Files.exists(config()) ? JsonParser.parseString(Files.readString(config())).getAsJsonObject() : new JsonObject();
            checksAllowed = checksConsented(config);
            asked = config.has("consentVersion") && config.get("consentVersion").getAsInt() == 2
                    && config.has("asked") && config.get("asked").getAsBoolean();
        } catch (Exception ignored) { checksAllowed = false; asked = false; }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                literal("tropimonupdates").then(literal(MOD_ID).executes(context -> {
                    MinecraftClient client = context.getSource().getClient();
                    client.execute(() -> client.setScreen(new UpdateScreen(client.currentScreen, pending)));
                    return 1;
                }))));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen || screen instanceof GameMenuScreen) client.execute(() -> {
                // Multiple independent mods take turns; never replace another mod's consent screen.
                if (client.currentScreen != screen) return;
                if (pending != null || (!asked && !shown)) {
                    shown = true;
                    client.setScreen(new UpdateScreen(screen, pending));
                }
            });
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (checksAllowed && !recentlyChecked()) check();
        });
    }

    static boolean checksConsented(JsonObject json) {
        try {
            return json.has("consentVersion") && json.get("consentVersion").getAsInt() == 2
                    && json.has("allowChecks") && json.get("allowChecks").getAsBoolean();
        } catch (RuntimeException invalid) { return false; }
    }

    private static Path config() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + "-updater.json").toAbsolutePath().normalize();
    }

    private static void settings(boolean allow) throws IOException {
        checksAllowed = allow; asked = true;
        if (!allow) pending = null;
        Path file = config(); TropimonUpdateInstaller.safe(file); Files.createDirectories(file.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("consentVersion", 2); json.addProperty("asked", true); json.addProperty("allowChecks", allow);
        // The legacy enabled=true setting never authorizes network access or downloads.
        json.addProperty("enabled", false);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp"); TropimonUpdateInstaller.safe(tmp);
        Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void check() {
        if (!checksAllowed || !BUSY.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
                Path installed = installedJar(mod);
                if (installed == null) throw new IOException("Unsupported mod origin");
                markChecked();
                JsonObject release = selectRelease(JsonParser.parseString(requestText(RELEASE_API, 2 * 1024 * 1024)).getAsJsonArray());
                if (release == null) return;
                String version = release.get("tag_name").getAsString().replaceFirst("^[vV]", "");
                if (compareVersions(version, mod.getMetadata().getVersion().getFriendlyString()) <= 0) return;
                ReleaseAsset jar = selectJar(release.getAsJsonArray("assets"));
                ReleaseAsset checksum = jar == null ? null : selectChecksum(release.getAsJsonArray("assets"), jar.name());
                if (jar == null || checksum == null) throw new IOException("Incomplete release");
                // Metadata only. Neither the checksum nor the JAR is fetched at this stage.
                if (checksAllowed) {
                    ReleaseOffer offer = new ReleaseOffer(version, jar, checksum, installed);
                    pending = offer;
                    MinecraftClient.getInstance().execute(() -> {
                        MinecraftClient c = MinecraftClient.getInstance();
                        if (pending == offer && (c.currentScreen instanceof TitleScreen || c.currentScreen instanceof GameMenuScreen))
                            c.setScreen(new UpdateScreen(c.currentScreen, offer));
                    });
                }
            } catch (Exception failure) { failed(failure); }
            finally { BUSY.set(false); }
        });
    }

    static boolean consumeApproval(ReleaseOffer offer, ReleaseOffer current, boolean accepted) {
        return accepted && offer != null && offer == current && offer.used.compareAndSet(false, true);
    }

    static JsonObject selectRelease(JsonArray releases) {
        JsonObject selected = null;
        String newest = "0";
        for (var value : releases) {
            try {
                JsonObject release = value.getAsJsonObject();
                if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()
                        || !release.has("body") || release.get("body").isJsonNull()
                        || !release.get("body").getAsString().contains(CONSENT_RELEASE)) continue;
                String version = release.get("tag_name").getAsString().replaceFirst("^[vV]", "");
                if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:\\+[A-Za-z0-9._-]+)?")) continue;
                if (compareVersions(version, newest) > 0) { selected = release; newest = version; }
            } catch (RuntimeException malformed) { /* An invalid entry cannot authorize a download. */ }
        }
        return selected;
    }

    private static void approve(ReleaseOffer offer) {
        if (!checksAllowed || offer == null || offer != pending || !BUSY.compareAndSet(false, true)) return;
        if (!consumeApproval(offer, pending, true)) { BUSY.set(false); return; }
        pending = null;
        CompletableFuture.runAsync(() -> {
            try {
                // Capture the installed content before any file download, not after it.
                String oldHash = TropimonUpdateInstaller.hash(offer.target);
                Path base = marker().getParent(); TropimonUpdateInstaller.safe(base); Files.createDirectories(base);
                Path directory = Files.createDirectory(base.resolve(UUID.randomUUID().toString()));
                String expectedHash = requestText(offer.checksum.url(), 512).trim().split("\\s+", 2)[0];
                if (!expectedHash.matches("(?i)[0-9a-f]{64}")) throw new IOException("Invalid checksum");
                Path staged = directory.resolve(offer.jar.name());
                download(offer.jar.url(), staged, MAX_JAR_SIZE);
                if (!TropimonUpdateInstaller.hash(staged).equalsIgnoreCase(expectedHash)) throw new IOException("Checksum mismatch");
                compatible(TropimonUpdateInstaller.metadata(staged));
                Properties job = TropimonUpdateInstaller.prepare(offer.target, staged, MOD_ID, offer.version);
                if (!oldHash.equals(job.getProperty("oldHash"))) throw new IOException("Installed mod changed during download");
                launchInstaller(directory, job);
                log.info("{}: update {} prepared with consent; waiting for Minecraft to stop.", MOD_ID, offer.version);
                notice(tr("Mise à jour préparée. Elle sera installée après fermeture de Minecraft.",
                        "Update prepared. It will install after Minecraft closes."));
            } catch (Exception failure) { failed(failure); }
            finally { BUSY.set(false); }
        });
    }

    private static void compatible(JsonObject metadata) throws Exception {
        if (!metadata.has("depends")) throw new IOException("Missing dependencies");
        for (var dependency : metadata.getAsJsonObject("depends").entrySet()) {
            Version installed = dependency.getKey().equals("java") ? Version.parse(Integer.toString(Runtime.version().feature()))
                    : FabricLoader.getInstance().getModContainer(dependency.getKey()).orElseThrow().getMetadata().getVersion();
            var value = dependency.getValue();
            List<String> ranges = new ArrayList<>();
            if (value.isJsonArray()) value.getAsJsonArray().forEach(v -> ranges.add(v.getAsString()));
            else ranges.add(value.getAsString());
            boolean valid = false;
            for (String range : ranges) valid |= VersionPredicate.parse(range).test(installed);
            if (!valid) throw new IOException("Update incompatible with installed dependencies");
        }
    }

    static Process launchInstaller(Path directory, Properties job) throws Exception {
        Path helper = directory.resolve("installer.jar");
        String resource = TropimonUpdateInstaller.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(helper));
             InputStream input = TropimonUpdateInstaller.class.getResourceAsStream("/" + resource)) {
            if (input == null) throw new IOException("Missing installer");
            output.putNextEntry(new JarEntry(resource)); input.transferTo(output); output.closeEntry();
        }
        // Gson is already supplied by Minecraft. Copy locally; never download a runtime or dependency.
        Path gsonSource = Path.of(JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path gson = directory.resolve("json-runtime.jar");
        Files.copy(gsonSource, gson);
        if (!TropimonUpdateInstaller.hash(gsonSource).equals(TropimonUpdateInstaller.hash(gson))) throw new IOException("Runtime copy mismatch");
        Path plan = directory.resolve("install.properties");
        try (OutputStream output = Files.newOutputStream(plan)) { job.store(output, "Private local update plan; do not share"); }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        if (!Files.isRegularFile(java)) throw new IOException("Bundled Java runtime unavailable");
        return new ProcessBuilder(java.toString(), "-cp", helper + File.pathSeparator + gson,
                TropimonUpdateInstaller.class.getName(), plan.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("install.log").toFile()).start();
    }

    private static Path installedJar(ModContainer mod) {
        var paths = mod.getOrigin().getPaths().stream().filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .map(p -> p.toAbsolutePath().normalize()).toList();
        if (paths.size() != 1) return null;
        Path jar = paths.getFirst();
        return jar.getParent().equals(FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolve("mods")) ? jar : null;
    }

    private static void failed(Exception failure) {
        log.warn("{}: update unavailable ({}); existing mod preserved.", MOD_ID, failure.getClass().getSimpleName());
        notice(tr("Mise à jour impossible. Le mod actuel est conservé. Consulte la Release officielle.",
                "Update unavailable. Your current mod is preserved. Check the official release."));
    }

    private static void notice(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) client.player.sendMessage(Text.literal(REPOSITORY + ": " + message), false);
            else client.getToastManager().add(net.minecraft.client.toast.SystemToast.create(client,
                    new net.minecraft.client.toast.SystemToast.Type(), Text.literal(REPOSITORY), Text.literal(message)));
        });
    }

    private static String tr(String french, String english) {
        return MinecraftClient.getInstance().options.language.startsWith("fr") ? french : english;
    }

    private static int compareVersions(String left, String right) { return TropimonUpdateInstaller.compare(left, right); }
    private record ReleaseAsset(String name, String url) {}
    static final class ReleaseOffer {
        final String version;
        final ReleaseAsset jar, checksum;
        final Path target;
        final AtomicBoolean used = new AtomicBoolean();
        ReleaseOffer(String version, ReleaseAsset jar, ReleaseAsset checksum, Path target) {
            this.version = version; this.jar = jar; this.checksum = checksum; this.target = target;
        }
    }

    private static final class UpdateScreen extends Screen {
        private final Screen parent;
        private final ReleaseOffer offer;
        private int scroll, maxScroll;
        UpdateScreen(Screen parent, ReleaseOffer offer) {
            super(Text.literal(REPOSITORY + " — " + tr("Mises à jour", "Updates")));
            this.parent = parent; this.offer = offer;
        }
        @Override protected void init() {
            int w = Math.min(300, width - 30), x = (width - w) / 2;
            addDrawableChild(ButtonWidget.builder(Text.literal(offer == null
                    ? tr("Autoriser les vérifications", "Allow update checks")
                    : tr("Télécharger et installer", "Download and install")), button -> {
                if (offer == null) {
                    try { settings(true); close(); check(); } catch (IOException e) { failed(e); close(); }
                } else { approve(offer); close(); }
            }).dimensions(x, height - 78, w, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(tr("Plus tard", "Later")), button -> close())
                    .dimensions(x, height - 54, w, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(tr("Désactiver les mises à jour", "Disable update checks")), button -> {
                try { settings(false); } catch (IOException e) { failed(e); }
                close();
            }).dimensions(x, height - 30, w, 20).build());
        }
        @Override public void close() {
            shown = true;
            if (pending == offer) pending = null;
            // Keep approval bound to the exact offer while its button callback runs.
            client.setScreen(parent);
        }
        @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
            scroll = Math.clamp(scroll - (int) (vertical * 22), 0, maxScroll);
            return true;
        }
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Screen.render draws the background and widgets before our disclosure text.
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
            String body = offer == null
                    ? tr("Autoriser ce mod à consulter GitHub au démarrage (au maximum toutes les 6 heures) ? GitHub reçoit la connexion réseau. Aucun fichier ne sera téléchargé sans un nouvel accord pour la version proposée. Refuser ne change aucune fonctionnalité du mod.",
                         "Allow this mod to check GitHub at startup (at most every 6 hours)? GitHub receives the network connection. No file will download without separate consent for the offered version. Declining does not affect the mod's features.")
                    : tr("Version proposée : ", "Offered version: ") + offer.version + "\n" + offer.jar.name()
                        + "\n" + tr("Source : GitHub / FastedCorsi / ", "Source: GitHub / FastedCorsi / ") + REPOSITORY
                        + "\n" + tr("Ce bouton télécharge le JAR et son SHA-256. Après vérification, un petit installateur Java local attend la fermeture de Minecraft, sauvegarde l'ancien JAR puis le remplace. Le launcher reste inchangé. La version sera active au prochain lancement.",
                                   "This button downloads the JAR and its SHA-256. After verification, a small local Java installer waits for Minecraft to close, backs up the old JAR and replaces it. Your launcher stays unchanged. The update is active on the next launch.");
            var lines = textRenderer.wrapLines(Text.literal(body), Math.min(560, width - 40));
            maxScroll = Math.max(0, lines.size() * 11 - (height - 126));
            scroll = Math.clamp(scroll, 0, maxScroll);
            context.enableScissor(10, 32, width - 10, height - 90);
            int y = 34 - scroll;
            for (var line : lines) {
                context.drawTextWithShadow(textRenderer, line, Math.max(20, (width - 560) / 2), y, 0xDDDDDD); y += 11;
            }
            context.disableScissor();
            if (scroll < maxScroll) context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(tr("Défiler pour lire la suite ↓", "Scroll to read more ↓")), width / 2, height - 89, 0xFFD580);
        }
    }

  private static boolean recentlyChecked() {
    Path marker = marker();
    try {
      if (Files.notExists(marker)) return false;
      Instant checkedAt = Instant.parse(Files.readString(marker).trim());
      return checkedAt.plus(CHECK_INTERVAL).isAfter(Instant.now());
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void markChecked() throws IOException {
    Path marker = marker();
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, Instant.now().toString(), StandardCharsets.UTF_8);
  }

  private static Path marker() {
    return FabricLoader.getInstance()
        .getConfigDir()
        .resolve(".tropimon-updates")
        .resolve(MOD_ID)
        .resolve("last-check.txt");
  }

  private static String requestText(String url, long maxBytes)
      throws IOException, InterruptedException {
    HttpRequest request = request(url);
    HttpResponse<InputStream> response =
        http().send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      response.body().close();
      throw new IOException("HTTP " + response.statusCode());
    }
    try (InputStream input = response.body()) {
      return new String(readLimited(input, maxBytes), StandardCharsets.UTF_8);
    }
  }

  private static void download(String url, Path destination, long maxBytes)
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response =
        http().send(request(url), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      response.body().close();
      throw new IOException("HTTP " + response.statusCode());
    }
    Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
    try (InputStream input = response.body();
        var output = Files.newOutputStream(temporary)) {
      byte[] buffer = new byte[16 * 1024];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > maxBytes) throw new IOException("Download too large");
        output.write(buffer, 0, read);
      }
    } catch (IOException | RuntimeException failure) {
      Files.deleteIfExists(temporary);
      throw failure;
    }
    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  private static HttpRequest request(String url) throws IOException {
    if (!(url.equals(RELEASE_API) || url.startsWith(RELEASE_DOWNLOAD_PREFIX))) {
      throw new IOException("Untrusted update URL");
    }
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Tropimon-" + MOD_ID + "-Updater")
        .GET()
        .build();
  }

  private static byte[] readLimited(InputStream input, long maxBytes) throws IOException {
    byte[] buffer = new byte[8192];
    try (var output = new java.io.ByteArrayOutputStream()) {
      long total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > maxBytes) throw new IOException("Response too large");
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private static ReleaseAsset selectJar(JsonArray assets) throws IOException {
    if (assets == null) throw new IOException("Missing assets");
    ReleaseAsset selected = null;
    for (var element : assets) {
      JsonObject asset = element.getAsJsonObject();
      String name = asset.get("name").getAsString();
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.endsWith(".jar")
          && !lower.contains("sources")
          && !lower.contains("dev")
          && !lower.contains("local")) {
        if (selected != null) throw new IOException("Ambiguous release JARs");
        selected = checkedAsset(name, asset.get("browser_download_url").getAsString());
      }
    }
    return selected;
  }

  private static ReleaseAsset selectChecksum(JsonArray assets, String jarName) throws IOException {
    if (assets == null || jarName == null) return null;
    for (var element : assets) {
      JsonObject asset = element.getAsJsonObject();
      String name = asset.get("name").getAsString();
      if (name.equalsIgnoreCase(jarName + ".sha256")) {
        return checkedAsset(name, asset.get("browser_download_url").getAsString());
      }
    }
    return null;
  }

  private static ReleaseAsset checkedAsset(String name, String url) throws IOException {
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*")
        || !url.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
      throw new IOException("Unsafe release asset");
    }
    return new ReleaseAsset(name, url);
  }


}
