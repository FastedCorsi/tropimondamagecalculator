package fr.tropimon.damagecalc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;

/**
 * Updater autonome de ce mod. Aucune classe d'un autre mod Tropimon n'est requise.
 */
final class TropimonSelfUpdater {
    private static final String MOD_ID = "tropimon_damage_calc";
    private static final String REPOSITORY = "tropimondamagecalculator";
    private static final String RELEASE_API =
            "https://api.github.com/repos/FastedCorsi/" + REPOSITORY + "/releases/latest";
    private static final String RELEASE_DOWNLOAD_PREFIX =
            "https://github.com/FastedCorsi/" + REPOSITORY + "/releases/download/";
    private static final Duration CHECK_INTERVAL = Duration.ofHours(6);
    private static final long MAX_JAR_SIZE = 64L * 1024L * 1024L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private TropimonSelfUpdater() {
    }

    static void start(Logger logger) {
        if (!enabled(logger)) return;
        CompletableFuture.runAsync(() -> check(logger)).exceptionally(failure -> {
            logger.warn("Mise a jour automatique {} indisponible pour cette session ({}).",
                    MOD_ID, rootCause(failure).getClass().getSimpleName());
            return null;
        });
    }

    private static boolean enabled(Logger logger) {
        Path config = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + "-updater.json");
        try {
            if (Files.notExists(config)) {
                Files.createDirectories(config.getParent());
                Files.writeString(config, "{\n  \"enabled\": true\n}\n", StandardCharsets.UTF_8);
                return true;
            }
            JsonObject json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            return !json.has("enabled") || json.get("enabled").getAsBoolean();
        } catch (Exception failure) {
            logger.warn("Configuration de mise a jour {} illisible; mise a jour desactivee.", MOD_ID);
            return false;
        }
    }

    private static void check(Logger logger) {
        if (!isWindows()) {
            logger.info("Mise a jour automatique {}: installation differee disponible sous Windows uniquement.", MOD_ID);
            return;
        }
        ModContainer container = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
        if (container == null) return;
        Path installedJar = installedJar(container);
        if (installedJar == null || recentlyChecked()) return;

        try {
            JsonObject release = requestJson(RELEASE_API);
            String currentVersion = container.getMetadata().getVersion().getFriendlyString();
            String releaseVersion = release.get("tag_name").getAsString().replaceFirst("^[vV]", "");
            if (compareVersions(releaseVersion, currentVersion) <= 0) {
                markChecked();
                return;
            }
            ReleaseAsset jarAsset = selectJar(release.getAsJsonArray("assets"));
            if (jarAsset == null) {
                throw new IOException("Release assets incomplete");
            }
            ReleaseAsset checksumAsset = selectChecksum(release.getAsJsonArray("assets"), jarAsset.name());
            if (checksumAsset == null) throw new IOException("Release assets incomplete");

            Path updateDir = FabricLoader.getInstance().getConfigDir()
                    .resolve(".tropimon-updates").resolve(MOD_ID);
            Files.createDirectories(updateDir);
            String expectedHash = requestText(checksumAsset.url(), 512).trim().split("\\s+", 2)[0];
            if (!expectedHash.matches("(?i)[0-9a-f]{64}")) {
                throw new IOException("Invalid SHA-256 sidecar");
            }

            Path staged = updateDir.resolve(jarAsset.name());
            download(jarAsset.url(), staged, MAX_JAR_SIZE);
            String downloadedHash = sha256(staged);
            if (!downloadedHash.equalsIgnoreCase(expectedHash)) {
                Files.deleteIfExists(staged);
                throw new IOException("SHA-256 mismatch");
            }

            JarMetadata metadata = inspectJar(staged);
            if (!MOD_ID.equals(metadata.id())) {
                Files.deleteIfExists(staged);
                throw new IOException("Unexpected mod id");
            }
            if (compareVersions(metadata.version(), currentVersion) <= 0) {
                Files.deleteIfExists(staged);
                markChecked();
                return;
            }

            Path installer = updateDir.resolve("install-after-minecraft.ps1");
            Files.writeString(installer, WINDOWS_INSTALLER, StandardCharsets.UTF_8);
            armInstaller(installer, staged, installedJar, sha256(installedJar), downloadedHash);
            logger.info("Mise a jour {} {} preparee; installation automatique apres l'arret de Minecraft.",
                    MOD_ID, metadata.version());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            logger.warn("Verification de mise a jour {} echouee ({}).",
                    MOD_ID, failure.getClass().getSimpleName());
        }
    }

    private static Path installedJar(ModContainer container) {
        return container.getOrigin().getPaths().stream()
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .findFirst().map(Path::toAbsolutePath).map(Path::normalize).orElse(null);
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
        return FabricLoader.getInstance().getConfigDir()
                .resolve(".tropimon-updates").resolve(MOD_ID).resolve("last-check.txt");
    }

    private static JsonObject requestJson(String url) throws IOException, InterruptedException {
        String body = requestText(url, 2 * 1024 * 1024);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String requestText(String url, long maxBytes) throws IOException, InterruptedException {
        HttpRequest request = request(url);
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
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
        HttpResponse<InputStream> response = HTTP.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
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
                .GET().build();
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
        for (var element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jar") && !lower.contains("sources") && !lower.contains("dev")
                    && !lower.contains("local")) {
                return checkedAsset(name, asset.get("browser_download_url").getAsString());
            }
        }
        return null;
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
        if (name.contains("/") || name.contains("\\") || !url.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
            throw new IOException("Unsafe release asset");
        }
        return new ReleaseAsset(name, url);
    }

    private static JarMetadata inspectJar(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null || entry.getSize() > 1024 * 1024) throw new IOException("Missing metadata");
            try (InputStream input = zip.getInputStream(entry)) {
                JsonObject json = JsonParser.parseString(
                        new String(readLimited(input, 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
                return new JarMetadata(json.get("id").getAsString(), json.get("version").getAsString());
            }
        }
    }

    private static int compareVersions(String left, String right) {
        int[] a = numericParts(left);
        int[] b = numericParts(right);
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int av = index < a.length ? a[index] : 0;
            int bv = index < b.length ? b[index] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] numericParts(String version) {
        String core = version.split("[+-]", 2)[0];
        String[] parts = core.replaceFirst("^[vV]", "").split("\\.");
        int[] values = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try {
                values[index] = Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ignored) {
                values[index] = 0;
            }
        }
        return values;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void armInstaller(Path script, Path staged, Path target,
                                     String oldHash, String newHash) throws IOException {
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                "-ParentPid", Long.toString(ProcessHandle.current().pid()),
                "-Staged", staged.toString(), "-Target", target.toString(),
                "-ExpectedOldHash", oldHash, "-NewHash", newHash, "-ModId", MOD_ID)
                .redirectErrorStream(true)
                .redirectOutput(script.resolveSibling("install.log").toFile())
                .start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private record ReleaseAsset(String name, String url) {
    }

    private record JarMetadata(String id, String version) {
    }

    private static final String WINDOWS_INSTALLER = """
            param(
                [Parameter(Mandatory=$true)][long]$ParentPid,
                [Parameter(Mandatory=$true)][string]$Staged,
                [Parameter(Mandatory=$true)][string]$Target,
                [Parameter(Mandatory=$true)][string]$ExpectedOldHash,
                [Parameter(Mandatory=$true)][string]$NewHash,
                [Parameter(Mandatory=$true)][string]$ModId
            )
            $ErrorActionPreference = 'Stop'
            while ($null -ne (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) {
                Start-Sleep -Milliseconds 500
            }
            if (-not (Test-Path -LiteralPath $Staged -PathType Leaf)) { exit 2 }
            if (-not (Test-Path -LiteralPath $Target -PathType Leaf)) { exit 3 }
            if ((Get-FileHash -LiteralPath $Staged -Algorithm SHA256).Hash -ne $NewHash) { exit 4 }
            if ((Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash -ne $ExpectedOldHash) { exit 5 }
            $modsDir = Split-Path -LiteralPath $Target -Parent
            $instanceDir = Split-Path -LiteralPath $modsDir -Parent
            $backupDir = Join-Path $instanceDir 'tropimon-updater-backups'
            New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
            $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
            $targetName = Split-Path -LiteralPath $Target -Leaf
            $backup = Join-Path $backupDir ($ModId + '-' + $stamp + '-' + $targetName)
            $temporary = Join-Path $modsDir ('.' + $targetName + '.updating')
            Copy-Item -LiteralPath $Staged -Destination $temporary -Force
            if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $NewHash) {
                Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
                exit 6
            }
            Move-Item -LiteralPath $Target -Destination $backup
            try {
                Move-Item -LiteralPath $temporary -Destination $Target
                Remove-Item -LiteralPath $Staged -Force -ErrorAction SilentlyContinue
            } catch {
                if (Test-Path -LiteralPath $temporary) {
                    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
                }
                if ((Test-Path -LiteralPath $backup) -and -not (Test-Path -LiteralPath $Target)) {
                    Move-Item -LiteralPath $backup -Destination $Target
                }
                throw
            }
            """;
}

