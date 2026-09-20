package fr.tropimon.damagecalc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;

/** Standalone local installer. No Minecraft, launcher or other Tropimon mod is required. */
public final class TropimonUpdateInstaller {
    private TropimonUpdateInstaller() {}

    public static void main(String[] args) {
        Path plan = args.length == 1 ? Path.of(args[0]).toAbsolutePath().normalize() : null;
        if (plan == null) System.exit(2);
        try {
            Properties job = readPlan(plan);
            status(plan, "waiting");
            long pid = Long.parseLong(job.getProperty("parentPid"));
            Instant started = Instant.parse(job.getProperty("parentStarted"));
            Path instance = Path.of(job.getProperty("target")).getParent().getParent();
            long deadline = System.nanoTime() + java.time.Duration.ofDays(2).toNanos();
            while (parentAlive(pid, started) || minecraftRunning(instance)) {
                if (System.nanoTime() > deadline) throw new IOException("Waiting expired");
                Thread.sleep(1000);
            }
            install(job);
            status(plan, "installed");
        } catch (Exception failure) {
            // A public log must never contain local paths, account names or a raw exception message.
            System.err.println("Update blocked: " + failure.getClass().getSimpleName());
            try { status(plan, "blocked"); } catch (IOException ignored) { }
            System.exit(2);
        }
    }

    static Properties prepare(Path target, Path staged, String modId, String version) throws Exception {
        target = target.toAbsolutePath().normalize();
        staged = staged.toAbsolutePath().normalize();
        safe(target); safe(staged);
        Path instance = instance(target);
        if (staged.startsWith(instance.resolve("mods")) || staged.startsWith(instance.resolve("mods-user")))
            throw new IOException("Staging must be outside loaded mods");
        JsonObject old = metadata(target), next = metadata(staged);
        if (!modId.equals(old.get("id").getAsString()) || !modId.equals(next.get("id").getAsString())
                || !version.equals(next.get("version").getAsString())
                || compare(version, old.get("version").getAsString()) <= 0)
            throw new IOException("Unexpected mod or version");
        unique(target.getParent(), target, modId);
        Properties p = new Properties();
        p.setProperty("target", target.toString()); p.setProperty("staged", staged.toString());
        p.setProperty("id", modId); p.setProperty("version", version);
        p.setProperty("oldHash", hash(target)); p.setProperty("newHash", hash(staged));
        p.setProperty("parentPid", Long.toString(ProcessHandle.current().pid()));
        p.setProperty("parentStarted", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        Path managed = instance.resolve("mods-user"), tracker = instance.getParent().resolve("user-mods-tracked.json");
        if (Files.exists(managed, LinkOption.NOFOLLOW_LINKS) || Files.exists(tracker, LinkOption.NOFOLLOW_LINKS)) {
            Path copy = managed.resolve(target.getFileName());
            safe(copy); safe(tracker);
            tracked(tracker, target.getFileName().toString());
            unique(managed, copy, modId);
            if (!hash(copy).equals(p.getProperty("oldHash"))) throw new IOException("Managed copy differs");
            p.setProperty("managed", copy.toString());
            p.setProperty("tracker", tracker.toString()); p.setProperty("trackerHash", hash(tracker));
        }
        return p;
    }

    static void install(Properties p) throws Exception {
        Path target = Path.of(p.getProperty("target")), staged = Path.of(p.getProperty("staged"));
        Path instance = instance(target);
        safe(instance); safe(staged);
        Path archiveRoot = instance.resolve("mod-archive");
        safe(archiveRoot); Files.createDirectories(archiveRoot); safe(archiveRoot);
        // One lock per mod; unrelated mods and launchers remain untouched.
        String id = p.getProperty("id");
        if (!id.matches("[a-z0-9_\\-]+")) throw new IOException("Invalid mod id");
        Path lockPath = archiveRoot.resolve(id + ".lock"); safe(lockPath);
        try (FileChannel mutex = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock exclusive = mutex.tryLock()) {
            if (exclusive == null) throw new IOException("Another update is installing");
            verify(p);
            if (minecraftRunning(instance)) throw new IOException("Minecraft restarted");
            Path archive = Files.createDirectory(archiveRoot.resolve(id + "-" + UUID.randomUUID()));
            Path incoming = archive.resolve("incoming.jar");
            Files.copy(staged, incoming);
            if (!hash(incoming).equals(p.getProperty("newHash"))) throw new IOException("Copy mismatch");
            List<Path> targets = new ArrayList<>(); targets.add(target);
            if (p.containsKey("managed")) targets.add(Path.of(p.getProperty("managed")));
            List<Path> incomingCopies = new ArrayList<>(); incomingCopies.add(incoming);
            for (int i = 1; i < targets.size(); i++) {
                Path copy = archive.resolve("incoming-" + i + ".jar");
                Files.copy(incoming, copy);
                if (!hash(copy).equals(p.getProperty("newHash"))) throw new IOException("Managed copy mismatch");
                incomingCopies.add(copy);
            }
            List<FileChannel> channels = new ArrayList<>();
            List<FileLock> locks = new ArrayList<>();
            List<Path> moved = new ArrayList<>();
            try {
                for (Path file : targets) {
                    Set<OpenOption> options = new HashSet<>(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
                    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                        options.add(com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ);
                        options.add(com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE);
                    }
                    FileChannel channel = FileChannel.open(file, options);
                    channels.add(channel);
                    FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true);
                    if (lock == null) throw new IOException("Target is locked");
                    locks.add(lock);
                    if (!hash(channel).equals(p.getProperty("oldHash"))) throw new IOException("Locked target changed");
                }
                verifyTracker(p);
                if (minecraftRunning(instance)) throw new IOException("Minecraft restarted");
                for (int i = 0; i < targets.size(); i++) {
                    Path file = targets.get(i), backup = archive.resolve("before-" + i + ".jar");
                    // Never overwrite an existing path. The approved filename stays registered with the launcher.
                    Files.move(file, backup); moved.add(file);
                    Files.move(incomingCopies.get(i), file);
                }
                for (Path file : targets) if (!hash(file).equals(p.getProperty("newHash")))
                    throw new IOException("Installed copy mismatch");
                verifyTracker(p);
            } catch (Exception failure) {
                for (int i = moved.size() - 1; i >= 0; i--) {
                    Path file = moved.get(i), backup = archive.resolve("before-" + i + ".jar");
                    try {
                        safe(file); safe(backup);
                        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                            if (!hash(file).equals(p.getProperty("newHash"))) continue;
                            Files.move(file, archive.resolve("failed-" + i + ".jar"));
                        }
                        Files.move(backup, file);
                    } catch (Exception restoreFailure) { failure.addSuppressed(restoreFailure); }
                }
                throw failure;
            } finally {
                for (FileLock lock : locks) lock.close();
                for (FileChannel channel : channels) channel.close();
            }
            for (int i = 0; i < targets.size(); i++)
                if (!hash(archive.resolve("before-" + i + ".jar")).equals(p.getProperty("oldHash")))
                    throw new IOException("Backup mismatch");
        }
    }

    private static void verify(Properties p) throws Exception {
        Path target = Path.of(p.getProperty("target")), staged = Path.of(p.getProperty("staged"));
        safe(target); safe(staged); instance(target);
        if (!hash(target).equals(p.getProperty("oldHash")) || !hash(staged).equals(p.getProperty("newHash")))
            throw new IOException("Files changed since consent");
        JsonObject next = metadata(staged);
        if (!p.getProperty("id").equals(next.get("id").getAsString())
                || !p.getProperty("version").equals(next.get("version").getAsString()))
            throw new IOException("Unexpected staged metadata");
        unique(target.getParent(), target, p.getProperty("id"));
        Path managed = target.getParent().getParent().resolve("mods-user");
        Path tracker = target.getParent().getParent().getParent().resolve("user-mods-tracked.json");
        boolean hasManaged = Files.exists(managed, LinkOption.NOFOLLOW_LINKS) || Files.exists(tracker, LinkOption.NOFOLLOW_LINKS);
        if (hasManaged != p.containsKey("managed")) throw new IOException("Launcher layout changed");
        if (hasManaged) {
            Path copy = Path.of(p.getProperty("managed")); safe(copy);
            if (!copy.equals(managed.resolve(target.getFileName()))) throw new IOException("Unexpected managed target");
            if (!hash(copy).equals(p.getProperty("oldHash"))) throw new IOException("Managed target changed");
            unique(managed, copy, p.getProperty("id"));
        }
        verifyTracker(p);
    }

    private static void verifyTracker(Properties p) throws Exception {
        if (!p.containsKey("tracker")) return;
        Path tracker = Path.of(p.getProperty("tracker")); safe(tracker);
        if (!hash(tracker).equals(p.getProperty("trackerHash"))) throw new IOException("Launcher tracking changed");
        tracked(tracker, Path.of(p.getProperty("target")).getFileName().toString());
    }

    private static void tracked(Path tracker, String name) throws IOException {
        if (Files.size(tracker) > 1024 * 1024) throw new IOException("Tracking too large");
        var array = JsonParser.parseString(Files.readString(tracker)).getAsJsonArray();
        Set<String> seen = new HashSet<>(); int count = 0;
        for (var value : array) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Unknown tracking format");
            String leaf = value.getAsString();
            if (leaf.isBlank() || leaf.contains("/") || leaf.contains("\\") || leaf.contains(":")
                    || leaf.equals(".") || leaf.equals("..") || !seen.add(leaf.toLowerCase(Locale.ROOT)))
                throw new IOException("Unsafe tracking entry");
            if (leaf.equals(name)) count++;
        }
        if (count != 1) throw new IOException("Mod not registered by launcher");
    }

    private static void unique(Path directory, Path expected, String id) throws IOException {
        safe(directory); int count = 0;
        try (var files = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path file : files) {
                safe(file);
                JsonObject json = metadata(file);
                if (json.has("id") && id.equals(json.get("id").getAsString())) {
                    if (!file.equals(expected)) throw new IOException("Duplicate mod");
                    count++;
                }
            }
        }
        if (count != 1) throw new IOException("Ambiguous mod installation");
    }

    static JsonObject metadata(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return new JsonObject(); // Non-Fabric libraries may also be present.
            if (entry.getSize() > 1024 * 1024) throw new IOException("Metadata too large");
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("Metadata too large");
                return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
    }

    static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(FileChannel channel) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16384);
        channel.position(0);
        while (channel.read(buffer) != -1) {
            buffer.flip(); digest.update(buffer); buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void safe(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.equals(path)) throw new IOException("Noncanonical path");
        for (Path p = absolute; p != null; p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther() || !p.toRealPath().equals(p))
                    throw new IOException("Redirected path");
            }
        }
    }

    private static Path instance(Path target) throws IOException {
        if (!target.isAbsolute() || !target.normalize().equals(target) || target.getParent() == null
                || !target.getParent().getFileName().toString().equals("mods")) throw new IOException("Unsupported mod origin");
        return target.getParent().getParent();
    }

    static int compare(String left, String right) {
        String[] a = left.split("[+]", 2)[0].split("\\."), b = right.split("[+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int delta = Integer.compare(i < a.length ? Integer.parseInt(a[i]) : 0, i < b.length ? Integer.parseInt(b[i]) : 0);
            if (delta != 0) return delta;
        }
        return 0;
    }

    static boolean parentAlive(long pid, Instant started) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .filter(p -> p.info().startInstant().map(started::equals).orElse(true)).isPresent();
    }

    static boolean minecraftRunning(Path instance) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            return minecraftRunningWindows(instance);
        try (var processes = ProcessHandle.allProcesses()) {
            for (ProcessHandle p : processes.toList()) {
                if (p.pid() == ProcessHandle.current().pid()) continue;
                var info = p.info();
                String command = info.command().orElse("").toLowerCase(Locale.ROOT);
                if (!(command.endsWith("java") || command.endsWith("java.exe") || command.endsWith("javaw.exe"))) continue;
                String[] args = info.arguments().orElse(null);
                if (args == null) throw new IOException("Java process cannot be inspected");
                for (int i = 0; i < args.length; i++) {
                    String dir = args[i].equals("--gameDir") && i + 1 < args.length ? args[i + 1]
                            : args[i].startsWith("--gameDir=") ? args[i].substring(10) : null;
                    if (dir != null && Path.of(dir).toAbsolutePath().normalize().equals(instance)) return true;
                }
            }
        }
        return false;
    }

    static String windowsProcessQuery() {
        return "$ErrorActionPreference='Stop'; Import-Module (Join-Path $PSHOME 'Modules/CimCmdlets/CimCmdlets.psd1'); "
                + "Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1'); "
                + "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                // CIM may keep a process that exited while the snapshot was being read.
                // Re-query only missing command lines; a live uninspectable process still blocks.
                + "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | ForEach-Object { "
                + "$item=$_; if ([string]::IsNullOrWhiteSpace($item.CommandLine)) { "
                + "$item=Get-CimInstance Win32_Process -Filter ('ProcessId=' + $item.ProcessId) }; "
                + "if ($null -ne $item) { $item | Select-Object ProcessId,CommandLine } }) | ConvertTo-Json -Compress";
    }

    private static boolean minecraftRunningWindows(Path instance) throws IOException {
        // ProcessHandle.arguments() is unavailable on Windows. Use the OS's read-only process query.
        Path executable = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String encoded = Base64.getEncoder().encodeToString(windowsProcessQuery().getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        try {
            Process query = new ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // The query returns only Java processes. Drain concurrently to avoid a pipe-size deadlock.
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return query.getInputStream().readNBytes(2 * 1024 * 1024 + 1); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            if (!query.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                query.destroy(); throw new IOException("Process query timed out");
            }
            byte[] bytes = output.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (query.exitValue() != 0 || bytes.length > 2 * 1024 * 1024) throw new IOException("Process query unavailable");
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
            return windowsSnapshotRunning(json, instance);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Process query interrupted", interrupted);
        } catch (Exception failure) { throw new IOException("Process query failed", failure); }
    }

    static boolean windowsSnapshotRunning(String json, Path instance) throws IOException {
        if (json.isEmpty()) return false;
        var parsed = JsonParser.parseString(json);
        var entries = parsed.isJsonArray() ? parsed.getAsJsonArray() : new com.google.gson.JsonArray();
        if (parsed.isJsonObject()) entries.add(parsed);
        for (var entry : entries) {
            var process = entry.getAsJsonObject();
            if (process.get("ProcessId").getAsLong() == ProcessHandle.current().pid()) continue;
            if (!process.has("CommandLine") || process.get("CommandLine").isJsonNull()
                    || process.get("CommandLine").getAsString().isBlank()) throw new IOException("Java process cannot be inspected");
            if (sameInstance(process.get("CommandLine").getAsString(), instance)) return true;
        }
        return false;
    }

    static boolean sameInstance(String command, Path instance) throws IOException {
        var match = java.util.regex.Pattern.compile("(?:^|\\s)\"?--gameDir\"?(?:\\s+|=)(?:\"([^\"]+)\"|([^\\s\"]+))").matcher(command);
        if (!match.find()) {
            if (java.util.regex.Pattern.compile("(?:^|\\s)\"?(?:net\\.fabricmc\\.loader\\.impl\\.launch\\.knot\\.KnotClient|net\\.minecraft\\.client\\.main\\.Main)\"?(?:\\s|$)").matcher(command).find())
                throw new IOException("Minecraft instance cannot be inspected");
            return false;
        }
        Path dir = Path.of(match.group(1) != null ? match.group(1) : match.group(2));
        if (!dir.isAbsolute()) throw new IOException("Relative game directory cannot be inspected");
        return dir.normalize().equals(instance);
    }

    static Properties readPlan(Path plan) throws IOException {
        safe(plan);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(plan)) { properties.load(input); }
        return properties;
    }

    static void status(Path plan, String state) throws IOException {
        Path path = plan.resolveSibling("status.txt"); safe(path);
        Files.writeString(path, state + "\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
