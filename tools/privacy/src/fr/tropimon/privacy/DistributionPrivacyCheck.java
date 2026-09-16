package fr.tropimon.privacy;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Build-only privacy gate. Findings contain locations and categories, never matched values. */
public final class DistributionPrivacyCheck {
    public static final String AUTHOR = "By FastedCorsi";
    private static final int MAX_ENTRY = 16 * 1024 * 1024;
    private static final int MAX_TOTAL = 128 * 1024 * 1024;
    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();
    private static final Set<String> ROOT_FILES = Set.of("AGENTS.md", "README.md", "LICENSE", "NOTICE",
            ".gitignore", ".gitattributes", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat");
    private static final Set<String> LOCAL_DIRS = Set.of(".git", ".gradle", ".privacy", "build", "out", "run", "logs",
            "mods", "exports", "backups", ".idea", ".vscode", "node_modules", "supabase",
            "tropimon-bid-maker", "tropimon-paris-bridge");
    private static final Set<String> PRIVATE_DIRS = Set.of(".git", ".env", ".privacy", "logs", "screenshots",
            "backups", "saves", "config", "exports", "node_modules");
    private static final Pattern NOREPLY = Pattern.compile("(?:[0-9]+\\+)?FastedCorsi@users\\.noreply\\.github\\.com", Pattern.CASE_INSENSITIVE);
    static {
        RULES.put("personal-home-path", Pattern.compile("(?i)(?:[A-Z]:[\\\\/]+(?:Users|Documents and Settings)[\\\\/]+[^\\\\/\\s\"'<>]+|/(?:Users|home)/[^/\\s\"'<>]+)"));
        RULES.put("email-needs-review", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        RULES.put("private-key", Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"));
        RULES.put("credential-token", Pattern.compile("(?<![A-Za-z0-9_])(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}|\\bAKIA[0-9A-Z]{16}\\b"));
        RULES.put("jwt-needs-review", Pattern.compile("\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}"));
        RULES.put("literal-secret", Pattern.compile("(?i)\\b(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token)\\b[\"']?\\s*[:=]\\s*[\"'][^\"'\\s]{12,}[\"']"));
        RULES.put("credential-url", Pattern.compile("https?://[^/\\s:@]+:[^/\\s@]+@|https?://[^/\\s]+/api/webhooks/[0-9]+/[^\\s\"']+"));
    }

    private final List<String> privateTerms;
    private final Map<String, String> reviewedAssets;
    private final Set<String> findings = new LinkedHashSet<>();
    private int checked;
    private long bytesRead;
    private int ownMetadata;

    public DistributionPrivacyCheck(List<String> privateTerms, Map<String, String> reviewedAssets) {
        this.privateTerms = List.copyOf(privateTerms);
        this.reviewedAssets = Map.copyOf(reviewedAssets);
    }

    public List<String> findings() { return List.copyOf(findings); }

    public static void main(String[] args) {
        try {
            if (args.length < 2) throw new IOException("Arguments required");
            Path root = Path.of(args[0]).toAbsolutePath().normalize();
            if (args[1].equals("git-identity")) {
                verifyGitIdentity(root);
                System.out.println("Git author and committer: verified public identity.");
                return;
            }
            var check = new DistributionPrivacyCheck(localTerms(root), assets(root));
            if (args[1].equals("sources") || args[1].equals("source-zip")) {
                List<Path> files = inventory(root);
                for (Path file : files) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (!shareablePath(relative)) check.report(relative, "unreviewed-source-location");
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        check.report(relative, "symlink-or-nonregular-file");
                    } else {
                        check.scan(relative, Files.readAllBytes(file), 0);
                    }
                }
                check.requireOwnMetadata();
                check.finish();
                if (args[1].equals("source-zip")) {
                    Path output = Path.of(args[2]);
                    Files.createDirectories(output.toAbsolutePath().getParent());
                    try (var zip = new ZipOutputStream(Files.newOutputStream(output))) {
                        for (Path file : files) {
                            ZipEntry entry = new ZipEntry(root.relativize(file).toString().replace('\\', '/'));
                            entry.setTime(0);
                            zip.putNextEntry(entry);
                            Files.copy(file, zip);
                            zip.closeEntry();
                        }
                    }
                }
            } else if (args[1].equals("archives")) {
                for (int i = 2; i < args.length; i++) {
                    Path archive = Path.of(args[i]);
                    int previous = check.ownMetadata;
                    check.scan(archive.getFileName().toString(), Files.readAllBytes(archive), 0);
                    if (previous == check.ownMetadata) check.report(archive.getFileName().toString(), "missing-mod-metadata");
                }
                check.requireOwnMetadata();
                check.finish();
            } else throw new IOException("Unknown mode");
        } catch (Exception error) {
            // IOException paths and parser exception text can themselves contain private data.
            System.err.println("Privacy verification failed (" + error.getClass().getSimpleName() + "). No sensitive values printed.");
            System.exit(1);
        }
    }

    public void scan(String location, byte[] content, int depth) throws IOException {
        checked++;
        bytesRead += content.length;
        if (depth > 4 || content.length > MAX_ENTRY || bytesRead > MAX_TOTAL) throw new IOException("Archive limits");
        String normalized = location.replace('\\', '/');
        if (privatePath(normalized)) report(location, "excluded-private-content");
        scanText(location + ":name", normalized);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
            int entries = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if (++entries > 10000) throw new IOException("Too many entries");
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("../") || name.contains(":")) report(location, "unsafe-archive-path");
                    if (!entry.isDirectory()) scan(location + "!/" + name, zip.readNBytes(MAX_ENTRY + 1), depth + 1);
                }
            }
            if (entries == 0) report(location, "empty-or-invalid-archive");
        } else if (lower.endsWith(".class")) {
            scanClass(location, content);
        } else if (lower.endsWith(".png")) {
            // Only reviewed runtime artwork is publishable. Screenshots/unknown images require review.
            String key = normalized.substring(normalized.lastIndexOf("!/") + 2);
            if (normalized.lastIndexOf("!/") < 0) key = normalized;
            if (key.startsWith("src/main/resources/")) key = key.substring("src/main/resources/".length());
            String expected = reviewedAssets.get(key);
            if (expected == null || !expected.equalsIgnoreCase(sha256(content))) report(location, "unreviewed-image");
            scanText(location, new String(content, StandardCharsets.ISO_8859_1));
        } else {
            String text = decodeText(content);
            scanText(location, text);
            if (lower.endsWith(".json")) scanJson(location, JsonParser.parseString(text));
            if (lower.endsWith("fabric.mod.json")) scanMetadata(location, content);
            if (!textPath(lower)) report(location, "unreviewed-binary-or-file-type");
        }
    }

    private void scanJson(String location, JsonElement json) {
        if (json.isJsonObject()) {
            json.getAsJsonObject().entrySet().forEach(entry -> {
                scanText(location + ":json-key", entry.getKey());
                scanJson(location, entry.getValue());
            });
        } else if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(value -> scanJson(location, value));
        } else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            scanText(location + ":json-value", json.getAsString());
        }
    }

    private static String decodeText(byte[] content) {
        if (content.length >= 2 && content[0] == (byte) 0xff && content[1] == (byte) 0xfe) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        if (content.length >= 2 && content[0] == (byte) 0xfe && content[1] == (byte) 0xff) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    public void scanText(String location, String text) {
        for (var rule : RULES.entrySet()) {
            var matcher = rule.getValue().matcher(text);
            while (matcher.find()) {
                if (rule.getKey().equals("email-needs-review") && NOREPLY.matcher(matcher.group()).matches()) continue;
                report(location + ":" + (1 + text.substring(0, matcher.start()).chars().filter(c -> c == '\n').count()), rule.getKey());
            }
        }
        for (String term : privateTerms) {
            if (!term.isBlank() && Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find()) report(location, "private-term");
        }
    }

    private void scanClass(String location, byte[] content) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(content))) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class");
            input.skipNBytes(4);
            int count = input.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> scanText(location + ":constant[" + i + "]", input.readUTF());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> { input.skipNBytes(8); i++; }
                    case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unknown constant tag");
                }
            }
        }
    }

    private void scanMetadata(String location, byte[] content) {
        JsonObject metadata = JsonParser.parseString(decodeText(content)).getAsJsonObject();
        String id = metadata.has("id") ? metadata.get("id").getAsString() : "";
        if (!id.equals("tropimon_damage_calc") && !id.equals("damagecalc_smoke")) return;
        ownMetadata++;
        var authors = metadata.getAsJsonArray("authors");
        if (authors == null || authors.size() != 1 || !authors.get(0).isJsonPrimitive()
                || !AUTHOR.equals(authors.get(0).getAsString())) report(location, "noncanonical-author");
    }

    public static boolean privatePath(String path) {
        for (String component : path.replace('\\', '/').toLowerCase(Locale.ROOT).split("[!/]+")) {
            if (PRIVATE_DIRS.contains(component) || component.startsWith(".env") || component.endsWith(".log")
                    || component.endsWith(".bak") || component.endsWith(".key") || component.endsWith(".pem")
                    || component.equals("options.txt") || component.equals("accounts.json")
                    || component.equals("launcher_accounts.json") || component.equals("session.json")
                    || component.equals("damagecalcruntimesmoke.class") || component.equals("legacydamagecache.class")
                    || component.endsWith("test.class")) return true;
        }
        return false;
    }

    private static boolean textPath(String path) {
        return path.matches(".*\\.(?:java|json|md|txt|properties|kts|mjs|ps1|bat|sh|yml|yaml|xml|svg|mf)$")
                || path.endsWith("gradlew") || path.endsWith(".gitignore") || path.endsWith(".gitattributes")
                || path.endsWith("license") || path.endsWith("notice");
    }

    private static boolean shareablePath(String path) {
        return ROOT_FILES.contains(path) || path.startsWith("src/") || path.startsWith("docs/")
                || path.startsWith("gradle/wrapper/") || path.startsWith("tools/") || path.startsWith(".github/");
    }

    private static List<Path> inventory(Path root) throws IOException, InterruptedException {
        List<Path> paths;
        if (Files.exists(root.resolve(".git"))) {
            String tracked = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard");
            paths = tracked.isEmpty() ? List.of() : java.util.Arrays.stream(tracked.split("\u0000"))
                    .distinct().map(root::resolve).toList();
        } else {
            try (var files = Files.walk(root)) {
                paths = files.filter(Files::isRegularFile).filter(file -> {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    String first = relative.split("/", 2)[0];
                    return !LOCAL_DIRS.contains(first) && !relative.startsWith("docs/verification-")
                            && !relative.startsWith("docs/screenshots/") && !relative.startsWith("mc-");
                }).toList();
            }
        }
        return paths.stream().sorted().toList();
    }

    private static List<String> localTerms(Path root) throws IOException, InterruptedException {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String account = System.getProperty("user.name", "");
        if (account.length() >= 3 && !Set.of("root", "runner", "user", "builder", "build", "ci", "fastedcorsi").contains(account.toLowerCase(Locale.ROOT))) terms.add(account);
        if (Files.exists(root.resolve(".git"))) {
            for (String variable : List.of("GIT_AUTHOR_IDENT", "GIT_COMMITTER_IDENT")) {
                String identity = git(root, "var", variable).trim();
                var match = Pattern.compile("^(.*?) <([^>]+)>.*$").matcher(identity);
                if (match.matches()) {
                    if (!match.group(1).equals("FastedCorsi") && !match.group(1).equals(AUTHOR)) terms.add(match.group(1));
                    if (!NOREPLY.matcher(match.group(2)).matches()) terms.add(match.group(2));
                }
            }
        }
        String configured = System.getenv("PRIVACY_TERMS_FILE");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toRealPath();
            if (path.startsWith(root.toRealPath())) throw new IOException("Private terms must stay outside repository");
            for (String line : Files.readAllLines(path)) if (!line.isBlank()) terms.add(line.trim());
        }
        return List.copyOf(terms);
    }

    private static Map<String, String> assets(Path root) throws IOException {
        JsonObject entries = JsonParser.parseString(Files.readString(root.resolve("tools/privacy/reviewed-assets.json"))).getAsJsonObject();
        Map<String, String> result = new LinkedHashMap<>();
        entries.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsJsonObject().get("sha256").getAsString()));
        return result;
    }

    private static String git(Path root, String... args) throws IOException, InterruptedException {
        ArrayList<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("Git command failed");
        return output;
    }

    private static void verifyGitIdentity(Path root) throws IOException, InterruptedException {
        for (String variable : List.of("GIT_AUTHOR_IDENT", "GIT_COMMITTER_IDENT")) {
            var match = Pattern.compile("^FastedCorsi <([^>]+)>.*$").matcher(git(root, "var", variable).trim());
            if (!match.matches() || !NOREPLY.matcher(match.group(1)).matches()) throw new IOException("Unverified Git identity");
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private void report(String location, String category) {
        String redacted = location;
        for (String term : privateTerms) if (!term.isBlank()) redacted = redacted.replaceAll("(?iu)" + Pattern.quote(term), "[redacted]");
        for (Pattern pattern : RULES.values()) redacted = pattern.matcher(redacted).replaceAll("[redacted]");
        findings.add(redacted + " : " + category);
    }

    private void requireOwnMetadata() { if (ownMetadata == 0) report("fabric.mod.json", "missing-mod-metadata"); }

    private void finish() throws IOException {
        if (!findings.isEmpty()) {
            findings.forEach(System.err::println);
            throw new IOException("Unresolved privacy findings");
        }
        System.out.println("Privacy check passed: " + checked + " files/entries, metadata " + AUTHOR + ", compiled constants and embedded resources checked.");
    }
}
