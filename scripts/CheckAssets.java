///usr/bin/env java "$0" "$@"; exit $?
// Presence Footsteps asset integrity checker.
//
// Usage:   java scripts/CheckAssets.java [repo-root]
// Requires JDK 17+. No external dependencies.
//
// Checks performed (mirrors of the runtime loading logic in
// eu.ha3.presencefootsteps.sound.Isolator and friends):
//   1. Every *.json under src/main/resources parses as strict JSON.
//   2. Every sound file declared in each assets/<ns>/sounds.json exists on disk
//      as assets/<ns>/sounds/<path>.ogg with the exact same case.
//   3. Orphan .ogg files never referenced by any sounds.json.
//   4. The stereo (presencefootsteps) and mono (presencefootstepsmono)
//      sounds.json declare the same sound events.
//   5. Acoustic names referenced by blockmap.json, golemmap.json,
//      primitivemap.json, config/blockmaps/entity/*.json and the names
//      hard-coded in the mod (swim_water, swim_lava, waterfine, lavafine,
//      swift, wing) all resolve to a config/acoustics/<name>.json file.
//      Unreferenced acoustics files are reported as warnings.
//   6. Every sound event name used inside config/acoustics/*.json exists in
//      sounds.json (walked according to the Acoustic.CODEC schema: basic,
//      events, simultaneous, delayed, probability, chance).
//      Event selector keys are validated against the State enum.
//   7. locomotionmap.json values are valid Locomotion names (exact-case,
//      anything else silently falls back to BIPED at runtime).
//   8. Every .ogg parses as an Ogg/Vorbis stream: page structure, page CRCs,
//      EOS flag, channel count and sample rate. Files under the mono
//      namespace must have exactly 1 channel (positional audio requirement).
//   9. Lang files: every key present in a translation but missing from
//      en_us.json is reported (orphans); missing translations are counted.
//
// Exit code 0 = no errors (warnings allowed), 1 = at least one error.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

public class CheckAssets {
    static final Set<String> SPECIAL_KEYS = Set.of("NOT_EMITTER", "MESSY_GROUND", "UNASSIGNED", "VANILLA");
    static final Set<String> HARDCODED_ACOUSTICS = Set.of("swim_water", "swim_lava", "waterfine", "lavafine", "swift", "wing");
    static final Set<String> STATE_NAMES = Set.of(
            "stand", "walk", "wander", "swim", "run", "jump", "land",
            "climb", "climb_run", "down", "down_run", "up", "up_run");
    static final Set<String> LOCOMOTION_NAMES = Set.of("NONE", "BIPED", "QUADRUPED", "FLYING", "FLYING_BIPED");

    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final Set<String> externalSoundRefs = new TreeSet<>();
    final Map<Path, Set<String>> dirListings = new HashMap<>();
    Path resources;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        CheckAssets checker = new CheckAssets();
        checker.resources = root.resolve("src/main/resources");
        if (!Files.isDirectory(checker.resources)) {
            System.err.println("src/main/resources not found under " + root);
            System.exit(2);
        }
        checker.run();
        System.exit(checker.report());
    }

    void run() throws IOException {
        Map<Path, Object> jsons = parseAllJson();
        Map<String, Map<String, Object>> soundsJsons = collectSoundsJsons(jsons);
        Set<Path> referencedOggs = checkSoundFiles(soundsJsons);
        checkOrphanOggs(referencedOggs);
        checkNamespaceParity(soundsJsons);
        Set<String> acousticFiles = collectAcousticFiles();
        checkAcousticReferences(jsons, acousticFiles);
        checkAcousticsContents(jsons, soundsJsons);
        checkLocomotionMap(jsons);
        checkOggStreams();
        checkLangFiles(jsons);
    }

    // ---------------------------------------------------------------- step 1

    Map<Path, Object> parseAllJson() throws IOException {
        Map<Path, Object> parsed = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) {
                    warnings.add("BOM at start of " + rel(p));
                    text = text.substring(1);
                }
                try {
                    parsed.put(p, new Json(text).parse());
                } catch (RuntimeException e) {
                    errors.add("Invalid JSON in " + rel(p) + ": " + e.getMessage());
                }
            }
        }
        infos.add(parsed.size() + " JSON files parsed");
        return parsed;
    }

    // ---------------------------------------------------------------- step 2

    Map<String, Map<String, Object>> collectSoundsJsons(Map<Path, Object> jsons) {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (var e : jsons.entrySet()) {
            Path p = e.getKey();
            Path parent = p.getParent();
            if (p.getFileName().toString().equals("sounds.json")
                    && parent.getParent() != null
                    && parent.getParent().getFileName().toString().equals("assets")
                    && e.getValue() instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) map;
                result.put(parent.getFileName().toString(), m);
            }
        }
        return result;
    }

    Set<Path> checkSoundFiles(Map<String, Map<String, Object>> soundsJsons) {
        Set<Path> referenced = new HashSet<>();
        for (var nsEntry : soundsJsons.entrySet()) {
            String ns = nsEntry.getKey();
            for (var event : nsEntry.getValue().entrySet()) {
                if (!(event.getValue() instanceof Map<?, ?> def)) {
                    errors.add("sounds.json(" + ns + "): event " + event.getKey() + " is not an object");
                    continue;
                }
                Object sounds = def.get("sounds");
                if (!(sounds instanceof List<?> list) || list.isEmpty()) {
                    errors.add("sounds.json(" + ns + "): event " + event.getKey() + " has no sounds");
                    continue;
                }
                for (Object s : list) {
                    String id = s instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : String.valueOf(s);
                    int colon = id.indexOf(':');
                    String fileNs = colon < 0 ? "minecraft" : id.substring(0, colon);
                    String path = colon < 0 ? id : id.substring(colon + 1);
                    Path ogg = resources.resolve("assets").resolve(fileNs).resolve("sounds")
                            .resolve(path.replace('/', java.io.File.separatorChar) + ".ogg");
                    if (!existsExactCase(ogg)) {
                        errors.add("sounds.json(" + ns + "): " + event.getKey() + " -> " + id
                                + " has no file " + rel(ogg) + " (checked case-sensitively)");
                    } else {
                        referenced.add(ogg.normalize());
                    }
                }
            }
        }
        return referenced;
    }

    /** Windows filesystems are case-insensitive; compare each path segment against the real directory listing. */
    boolean existsExactCase(Path p) {
        p = p.normalize();
        if (!Files.exists(p)) {
            return false;
        }
        Path cursor = resources;
        Path relative = resources.relativize(p);
        for (Path segment : relative) {
            Set<String> names = dirListings.computeIfAbsent(cursor, dir -> {
                try (Stream<Path> children = Files.list(dir)) {
                    Set<String> set = new HashSet<>();
                    children.forEach(c -> set.add(c.getFileName().toString()));
                    return set;
                } catch (IOException e) {
                    return Set.of();
                }
            });
            if (!names.contains(segment.toString())) {
                return false;
            }
            cursor = cursor.resolve(segment);
        }
        return true;
    }

    // ---------------------------------------------------------------- step 3

    void checkOrphanOggs(Set<Path> referenced) throws IOException {
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".ogg")).map(Path::normalize).toList()) {
                if (!referenced.contains(p)) {
                    warnings.add("Orphan ogg (not referenced by any sounds.json): " + rel(p));
                }
            }
        }
    }

    // ---------------------------------------------------------------- step 4

    void checkNamespaceParity(Map<String, Map<String, Object>> soundsJsons) {
        Map<String, Object> stereo = soundsJsons.get("presencefootsteps");
        Map<String, Object> mono = soundsJsons.get("presencefootstepsmono");
        if (stereo == null || mono == null) {
            errors.add("Missing sounds.json for presencefootsteps or presencefootstepsmono");
            return;
        }
        for (String key : stereo.keySet()) {
            if (!mono.containsKey(key)) {
                errors.add("Sound event " + key + " exists in stereo sounds.json but not in mono"
                        + " (other players' footsteps will be silent for it)");
            }
        }
        for (String key : mono.keySet()) {
            if (!stereo.containsKey(key)) {
                warnings.add("Sound event " + key + " exists only in mono sounds.json");
            }
        }
    }

    // ---------------------------------------------------------------- step 5

    Set<String> collectAcousticFiles() throws IOException {
        Path dir = resources.resolve("assets/presencefootsteps/config/acoustics");
        Set<String> names = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                list.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".json"))
                        .map(n -> n.substring(0, n.length() - 5))
                        .forEach(names::add);
            }
        }
        infos.add(names.size() + " acoustics definitions found");
        return names;
    }

    void checkAcousticReferences(Map<Path, Object> jsons, Set<String> acousticFiles) {
        Set<String> referenced = new HashSet<>(HARDCODED_ACOUSTICS);
        for (String hard : HARDCODED_ACOUSTICS) {
            if (!acousticFiles.contains(hard)) {
                errors.add("Acoustic '" + hard + "' is hard-coded in the mod but has no acoustics/" + hard + ".json");
            }
        }
        for (var e : jsons.entrySet()) {
            String relPath = rel(e.getKey()).replace('\\', '/');
            boolean isMap = relPath.endsWith("config/blockmap.json")
                    || relPath.endsWith("config/golemmap.json")
                    || relPath.endsWith("config/primitivemap.json")
                    || relPath.contains("config/blockmaps/entity/");
            if (!isMap || !(e.getValue() instanceof Map<?, ?> map)) {
                continue;
            }
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof String value)) {
                    errors.add(relPath + ": value of " + key + " is not a string");
                    continue;
                }
                if (SPECIAL_KEYS.contains(value.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                for (String name : value.split(",")) {
                    name = name.trim().toLowerCase(Locale.ROOT);
                    if (name.isEmpty()) {
                        continue;
                    }
                    referenced.add(name);
                    if (!acousticFiles.contains(name)) {
                        errors.add(relPath + ": " + key + " references unknown acoustic '" + name + "'");
                    }
                }
            }
        }
        for (String name : acousticFiles) {
            if (!referenced.contains(name)) {
                warnings.add("Acoustic '" + name + "' is defined but referenced by no map file"
                        + " (may still be used by third-party resource packs)");
            }
        }
    }

    // ---------------------------------------------------------------- step 6

    void checkAcousticsContents(Map<Path, Object> jsons, Map<String, Map<String, Object>> soundsJsons) {
        Map<String, Object> soundEvents = soundsJsons.getOrDefault("presencefootsteps", Map.of());
        for (var e : jsons.entrySet()) {
            String relPath = rel(e.getKey()).replace('\\', '/');
            if (!relPath.contains("config/acoustics/") || !(e.getValue() instanceof Map<?, ?> map)) {
                continue;
            }
            // The whole file is one acoustic (normally {"type": "events", ...}).
            walkAcoustic(relPath, "<root>", map, soundEvents);
        }
    }

    /** Mirrors Acoustic.CODEC: string = basic sound name, array = simultaneous, object dispatched on "type". */
    void walkAcoustic(String file, String context, Object node, Map<String, Object> soundEvents) {
        if (node instanceof String name) {
            requireSoundEvent(file, context, name, soundEvents);
        } else if (node instanceof List<?> list) {
            for (Object sub : list) {
                walkAcoustic(file, context, sub, soundEvents);
            }
        } else if (node instanceof Map<?, ?> map) {
            String type;
            if (map.containsKey("type")) {
                type = String.valueOf(map.get("type"));
            } else {
                type = "basic";
                warnings.add(file + ": " + context + ": acoustic object without explicit 'type'"
                        + " (Acoustic.CODEC requires one; assuming basic)");
            }
            switch (type) {
                case "basic" -> {
                    Object name = map.get("name");
                    if (name == null) {
                        errors.add(file + ": " + context + ": basic acoustic without a name");
                    } else {
                        requireSoundEvent(file, context, String.valueOf(name), soundEvents);
                    }
                }
                case "events" -> {
                    for (var sub : map.entrySet()) {
                        String key = String.valueOf(sub.getKey());
                        if (key.equals("type")) {
                            continue;
                        }
                        if (!STATE_NAMES.contains(key)) {
                            errors.add(file + ": " + context + ": unknown event selector key '" + key + "'");
                        }
                        walkAcoustic(file, context + "." + key, sub.getValue(), soundEvents);
                    }
                }
                case "simultaneous" -> walkAcoustic(file, context, map.get("acoustics"), soundEvents);
                case "delayed", "chance" -> walkAcoustic(file, context, map.get("acoustic"), soundEvents);
                case "probability" -> {
                    if (map.get("entries") instanceof List<?> entries) {
                        for (Object entry : entries) {
                            if (entry instanceof Map<?, ?> m) {
                                walkAcoustic(file, context, m.get("acoustic"), soundEvents);
                            }
                        }
                    } else {
                        errors.add(file + ": " + context + ": probability acoustic without 'entries' list");
                    }
                }
                default -> errors.add(file + ": " + context + ": unknown acoustic type '" + type + "'");
            }
        } else if (node == null) {
            errors.add(file + ": " + context + ": missing acoustic value");
        }
    }

    /**
     * Mirrors ImmediateSoundPlayer.getSoundId: an empty name is intentional
     * silence, a name containing ':' is an external sound event played as-is
     * (usually vanilla), anything else resolves inside the mod's namespaces.
     */
    void requireSoundEvent(String file, String context, String name, Map<String, Object> soundEvents) {
        if (name.isEmpty()) {
            return;
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            String ns = name.substring(0, colon);
            String path = name.substring(colon + 1);
            if (!ns.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9/._-]+")) {
                errors.add(file + ": " + context + ": '" + name + "' is not a valid sound identifier");
            } else if (ns.contains(".")) {
                warnings.add(file + ": " + context + ": namespace '" + ns + "' of '" + name
                        + "' looks like a typo (external sound event that cannot exist)");
            } else {
                externalSoundRefs.add(name);
            }
            return;
        }
        if (!soundEvents.containsKey(name)) {
            errors.add(file + ": " + context + " references sound event '" + name + "' absent from sounds.json");
        }
    }

    // ---------------------------------------------------------------- step 7

    void checkLocomotionMap(Map<Path, Object> jsons) {
        for (var e : jsons.entrySet()) {
            String relPath = rel(e.getKey()).replace('\\', '/');
            if (!relPath.endsWith("config/locomotionmap.json") || !(e.getValue() instanceof Map<?, ?> map)) {
                continue;
            }
            for (var entry : map.entrySet()) {
                String value = String.valueOf(entry.getValue());
                if (!LOCOMOTION_NAMES.contains(value)) {
                    errors.add(relPath + ": " + entry.getKey() + " has invalid stance '" + value
                            + "' (exact-case names only, silently falls back to BIPED)");
                }
            }
        }
    }

    // ---------------------------------------------------------------- step 8

    void checkOggStreams() throws IOException {
        int count = 0;
        Map<Integer, Integer> rates = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".ogg")).toList()) {
                count++;
                try {
                    OggInfo info = parseOgg(Files.readAllBytes(p));
                    rates.merge(info.sampleRate, 1, Integer::sum);
                    boolean isMono = rel(p).replace('\\', '/').contains("assets/presencefootstepsmono/");
                    if (isMono && info.channels != 1) {
                        errors.add(rel(p) + ": mono-namespace file has " + info.channels
                                + " channels (will not play positionally)");
                    }
                    if (info.channels < 1 || info.channels > 2) {
                        errors.add(rel(p) + ": suspicious channel count " + info.channels);
                    }
                    if (info.sampleRate < 8000 || info.sampleRate > 192000) {
                        errors.add(rel(p) + ": suspicious sample rate " + info.sampleRate);
                    }
                    if (!info.sawEos) {
                        warnings.add(rel(p) + ": stream has no EOS page (truncated file?)");
                    }
                } catch (RuntimeException ex) {
                    errors.add(rel(p) + ": " + ex.getMessage());
                }
            }
        }
        infos.add(count + " ogg files checked; sample rates: " + rates);
    }

    record OggInfo(int channels, int sampleRate, boolean sawEos) {}

    static OggInfo parseOgg(byte[] data) {
        int offset = 0;
        int pageIndex = 0;
        int channels = -1;
        int sampleRate = -1;
        boolean sawEos = false;
        while (offset < data.length) {
            if (offset + 27 > data.length) {
                throw new IllegalStateException("truncated page header at byte " + offset);
            }
            if (data[offset] != 'O' || data[offset + 1] != 'g' || data[offset + 2] != 'g' || data[offset + 3] != 'S') {
                throw new IllegalStateException("bad page magic at byte " + offset);
            }
            if (data[offset + 4] != 0) {
                throw new IllegalStateException("unsupported ogg version " + data[offset + 4]);
            }
            int headerType = data[offset + 5] & 0xFF;
            int segments = data[offset + 26] & 0xFF;
            if (offset + 27 + segments > data.length) {
                throw new IllegalStateException("truncated segment table at byte " + offset);
            }
            int bodySize = 0;
            for (int i = 0; i < segments; i++) {
                bodySize += data[offset + 27 + i] & 0xFF;
            }
            int pageSize = 27 + segments + bodySize;
            if (offset + pageSize > data.length) {
                throw new IllegalStateException("truncated page body at byte " + offset);
            }
            int storedCrc = readIntLE(data, offset + 22);
            if (oggCrc(data, offset, pageSize) != storedCrc) {
                throw new IllegalStateException("CRC mismatch on page " + pageIndex + " (corrupted file)");
            }
            if ((headerType & 0x04) != 0) {
                sawEos = true;
            }
            if (pageIndex == 0) {
                int body = offset + 27 + segments;
                if (bodySize >= 30 && data[body] == 1 && data[body + 1] == 'v' && data[body + 2] == 'o'
                        && data[body + 3] == 'r' && data[body + 4] == 'b' && data[body + 5] == 'i' && data[body + 6] == 's') {
                    channels = data[body + 11] & 0xFF;
                    sampleRate = readIntLE(data, body + 12);
                } else {
                    throw new IllegalStateException("first packet is not a vorbis identification header");
                }
            }
            offset += pageSize;
            pageIndex++;
        }
        if (channels < 0) {
            throw new IllegalStateException("no vorbis identification header found");
        }
        return new OggInfo(channels, sampleRate, sawEos);
    }

    static int readIntLE(byte[] d, int o) {
        return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
    }

    static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
            }
            CRC_TABLE[i] = r;
        }
    }

    /** Ogg page CRC: poly 0x04c11db7, init 0, CRC field treated as zero. */
    static int oggCrc(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            int b = (i >= 22 && i < 26) ? 0 : data[offset + i] & 0xFF;
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
        }
        return crc;
    }

    // ---------------------------------------------------------------- step 9

    void checkLangFiles(Map<Path, Object> jsons) {
        Map<String, Object> enUs = null;
        Map<String, Map<String, Object>> langs = new TreeMap<>();
        for (var e : jsons.entrySet()) {
            String relPath = rel(e.getKey()).replace('\\', '/');
            if (relPath.contains("assets/presencefootsteps/lang/") && e.getValue() instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) map;
                if (relPath.endsWith("en_us.json")) {
                    enUs = m;
                } else {
                    langs.put(relPath, m);
                }
            }
        }
        if (enUs == null) {
            errors.add("en_us.json not found");
            return;
        }
        for (var lang : langs.entrySet()) {
            int missing = 0;
            for (String key : lang.getValue().keySet()) {
                if (!enUs.containsKey(key)) {
                    warnings.add(lang.getKey() + ": key '" + key + "' does not exist in en_us.json (orphan)");
                }
            }
            for (String key : enUs.keySet()) {
                if (!lang.getValue().containsKey(key)) {
                    missing++;
                }
            }
            if (missing > 0) {
                infos.add(lang.getKey() + ": " + missing + "/" + enUs.size() + " keys untranslated (falls back to English)");
            }
        }
    }

    // ---------------------------------------------------------------- output

    String rel(Path p) {
        return resources.getParent().getParent().getParent().relativize(p.toAbsolutePath()).toString();
    }

    int report() {
        if (!externalSoundRefs.isEmpty()) {
            infos.add(externalSoundRefs.size() + " external (vanilla) sound events referenced by acoustics,"
                    + " verify in-game: " + externalSoundRefs);
        }
        errors.sort(Comparator.naturalOrder());
        warnings.sort(Comparator.naturalOrder());
        for (String info : infos) {
            System.out.println("[INFO] " + info);
        }
        for (String warning : warnings) {
            System.out.println("[WARN] " + warning);
        }
        for (String error : errors) {
            System.out.println("[ERROR] " + error);
        }
        System.out.printf("%n%d error(s), %d warning(s)%n", errors.size(), warnings.size());
        return errors.isEmpty() ? 0 : 1;
    }

    // ------------------------------------------------------- strict JSON parser

    static class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            Object value = parseValue();
            skipWs();
            if (i < s.length()) {
                throw err("trailing content");
            }
            return value;
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) {
                throw err("unexpected end of input");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                if (map.put(key, value) != null) {
                    throw err("duplicate key \"" + key + "\"");
                }
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw err("expected ',' or '}'");
                }
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw err("expected ',' or ']'");
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw err("invalid number");
            }
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw err("invalid literal");
            }
            i += word.length();
            return value;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (i >= s.length()) {
                throw err("unexpected end of input");
            }
            return s.charAt(i);
        }

        char next() {
            if (i >= s.length()) {
                throw err("unexpected end of input");
            }
            return s.charAt(i++);
        }

        void expect(char c) {
            if (next() != c) {
                i--;
                throw err("expected '" + c + "'");
            }
        }

        RuntimeException err(String message) {
            int line = 1;
            int col = 1;
            for (int j = 0; j < Math.min(i, s.length()); j++) {
                if (s.charAt(j) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new IllegalStateException(message + " at line " + line + ", column " + col);
        }
    }
}
