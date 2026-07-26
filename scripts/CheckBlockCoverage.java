///usr/bin/env java "$0" "$@"; exit $?
// Presence Footsteps block coverage checker.
//
// Cross-references the vanilla block registry against the mod's blockmap to
// list blocks that fall back to heuristic detection (primitivemap / sound
// type) instead of an explicit mapping. Also reports stale blockmap entries
// (unknown block ids or tags) and validates entity ids in golemmap.json and
// locomotionmap.json.
//
// Usage:
//   java scripts/CheckBlockCoverage.java <repo-root> <minecraft-jar> [<minecraft-sources-jar>]
//
// <minecraft-jar> is any jar containing assets/minecraft/blockstates/*.json
// and data/minecraft/tags/block/**.json for the target version. With ModDevGradle
// use build/moddev/artifacts/minecraft-patched-<version>-merged.jar (created by
// the createMinecraftArtifacts task, run automatically during a build).
// <minecraft-sources-jar> (optional) enables entity id validation from
// EntityTypes.java (...-sources.jar next to the merged jar).
//
// Blockmap key syntax mirrored from eu.ha3.presencefootsteps.world.StateLookup.Key:
//   [#]namespace:path[^meta][\[prop=value,...\]][.substrate]   or   *[.substrate]
//
// Exit code 0 = no errors (an unmapped block is informational, not an error).

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CheckBlockCoverage {
    final Set<String> blocks = new TreeSet<>();
    final Map<String, List<String>> tags = new HashMap<>();
    final Set<String> entities = new TreeSet<>();
    final List<String> errors = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java scripts/CheckBlockCoverage.java <repo-root> <minecraft-jar> [<minecraft-sources-jar>]");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        CheckBlockCoverage checker = new CheckBlockCoverage();
        checker.loadRegistry(Path.of(args[1]));
        if (args.length > 2) {
            checker.loadEntities(Path.of(args[2]));
        }
        checker.run(root);
    }

    void loadRegistry(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("assets/minecraft/blockstates/") && name.endsWith(".json")) {
                    blocks.add("minecraft:" + name.substring("assets/minecraft/blockstates/".length(), name.length() - 5));
                } else if (name.startsWith("data/minecraft/tags/block/") && name.endsWith(".json")) {
                    String tagName = "minecraft:" + name.substring("data/minecraft/tags/block/".length(), name.length() - 5);
                    String body = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    List<String> values = new ArrayList<>();
                    // Tag files only contain identifiers in string literals; a
                    // full JSON parse is not needed to collect them.
                    Matcher m = Pattern.compile("\"(#?[a-z0-9_.-]+:[a-z0-9/._-]+)\"").matcher(body);
                    while (m.find()) {
                        values.add(m.group(1));
                    }
                    tags.put(tagName, values);
                }
            }
        }
        System.out.println("[INFO] " + blocks.size() + " vanilla blocks, " + tags.size() + " block tags loaded");
    }

    void loadEntities(Path sourcesJar) throws IOException {
        try (ZipFile zip = new ZipFile(sourcesJar.toFile())) {
            // 26.2 keeps the id literals in EntityTypeIds (create("id")); 26.1 and
            // older versions inline them in EntityType/EntityTypes (register("id", ...)).
            for (String file : new String[] {
                    "net/minecraft/world/entity/EntityTypeIds.java",
                    "net/minecraft/world/entity/EntityType.java",
                    "net/minecraft/world/entity/EntityTypes.java"}) {
                ZipEntry entry = zip.getEntry(file);
                if (entry == null) {
                    continue;
                }
                String body = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?:create|register)\\(\\s*\"([a-z0-9_]+)\"").matcher(body);
                while (m.find()) {
                    entities.add("minecraft:" + m.group(1));
                }
                if (!entities.isEmpty()) {
                    break;
                }
            }
        }
        if (entities.isEmpty()) {
            System.out.println("[WARN] no entity ids found in " + sourcesJar + ", skipping entity id checks");
        } else {
            System.out.println("[INFO] " + entities.size() + " vanilla entity types loaded");
        }
    }

    void run(Path root) throws IOException {
        Path resources = root.resolve("src/main/resources");
        Set<String> mappedDefault = new TreeSet<>();
        Set<String> mappedOtherSubstrate = new TreeSet<>();

        for (String[] entry : readMapEntries(resources.resolve("assets/presencefootsteps/config/blockmap.json"))) {
            classifyBlockKey("blockmap.json", entry[0], mappedDefault, mappedOtherSubstrate);
        }
        Path entityMaps = resources.resolve("assets/minecraft/config/blockmaps/entity");
        if (Files.isDirectory(entityMaps)) {
            try (var list = Files.list(entityMaps)) {
                for (Path p : list.toList()) {
                    for (String[] entry : readMapEntries(p)) {
                        // Entity block maps refine sounds per entity; they do not
                        // affect global coverage, only key validity is checked.
                        classifyBlockKey(p.getFileName().toString(), entry[0], new TreeSet<>(), new TreeSet<>());
                    }
                }
            }
        }

        Set<String> unmapped = new TreeSet<>(blocks);
        unmapped.removeAll(mappedDefault);

        System.out.println("[INFO] " + mappedDefault.size() + " blocks explicitly mapped (default substrate), "
                + unmapped.size() + " fall back to primitive/heuristic detection");
        Set<String> unmappedOnlySubstrate = new TreeSet<>(unmapped);
        unmappedOnlySubstrate.retainAll(mappedOtherSubstrate);
        if (!unmappedOnlySubstrate.isEmpty()) {
            System.out.println("[INFO] of which mapped for a non-default substrate only: " + unmappedOnlySubstrate);
        }
        System.out.println();
        System.out.println("Blocks without an explicit default-substrate mapping (improvement candidates):");
        for (String block : unmapped) {
            System.out.println("  " + block);
        }

        if (!entities.isEmpty()) {
            System.out.println();
            for (String file : new String[] {"golemmap.json", "locomotionmap.json"}) {
                for (String[] entry : readMapEntries(resources.resolve("assets/presencefootsteps/config/" + file))) {
                    String id = entry[0].split("[@.\\[]")[0];
                    if (!id.startsWith("#") && !id.startsWith("*") && !entities.contains(id)) {
                        errors.add(file + ": unknown entity id " + id);
                    }
                }
            }
        }

        System.out.println();
        for (String error : errors) {
            System.out.println("[ERROR] " + error);
        }
        System.out.println(errors.size() + " error(s)");
        System.exit(errors.isEmpty() ? 0 : 1);
    }

    void classifyBlockKey(String file, String rawKey, Set<String> mappedDefault, Set<String> mappedOtherSubstrate) {
        boolean isTag = rawKey.startsWith("#");
        String key = isTag ? rawKey.substring(1) : rawKey;
        String id = key.split("[.\\[]")[0];
        boolean isWildcard = id.startsWith("*");
        if (id.contains("^")) {
            id = id.split("\\^")[0];
        }
        String rest = key.substring(id.length()).replaceFirst("\\[[^\\]]+\\]", "");
        boolean defaultSubstrate = !rest.contains(".");

        if (isWildcard) {
            return;
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        Set<String> target = defaultSubstrate ? mappedDefault : mappedOtherSubstrate;
        if (isTag) {
            Set<String> expanded = expandTag(id, new HashSet<>());
            if (expanded == null) {
                errors.add(file + ": key '" + rawKey + "' references unknown tag " + id);
            } else {
                target.addAll(expanded);
            }
        } else {
            if (id.startsWith("minecraft:") && !blocks.contains(id)) {
                errors.add(file + ": key '" + rawKey + "' references unknown block id " + id);
            }
            target.add(id);
        }
    }

    Set<String> expandTag(String tag, Set<String> seen) {
        if (!seen.add(tag)) {
            return Set.of();
        }
        List<String> values = tags.get(tag);
        if (values == null) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value.startsWith("#")) {
                Set<String> nested = expandTag(value.substring(1), seen);
                if (nested != null) {
                    result.addAll(nested);
                }
            } else {
                result.add(value);
            }
        }
        return result;
    }

    /** Returns [key, value] pairs of a flat string-to-string JSON object without a JSON library. */
    List<String[]> readMapEntries(Path file) throws IOException {
        List<String[]> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        String body = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        while (m.find()) {
            entries.add(new String[] {m.group(1), m.group(2)});
        }
        return entries;
    }
}
