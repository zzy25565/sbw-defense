package com.zzy.sbwdefense;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-vehicle defensive layout: where the APS tubes sit on the hull, and which faces carry ERA.
 *
 * <p>Reads {@code config/sbw_defense-vehicle-layouts.json}. A real hard-kill system is not bolted
 * onto every hull the same way - a tank carries two launchers on the turret cheeks, an Abrams
 * carries them on the turret flanks, a truck carries none - and ERA coverage differs just as much
 * (Relikt on the turret front and skirts, nothing on the engine deck).
 *
 * <p>Azimuth convention (hull relative, clockwise positive):
 * <pre>
 *          0°  front
 *    -90°/270°  left      +90°  right
 *          ±180°  rear
 * </pre>
 *
 * <p>Mount offset convention (blocks, relative to the hull origin, +X right, +Y up, +Z forward):
 * an omitted {@code mount} keeps the old behaviour of launching from the hull centre at y+1.2.
 *
 * <p>Vehicles without an entry fall back to {@code "default"} in the same file; when neither
 * exists the uniform {@code defense.aps.*} / {@code defense.era.*} config is used.
 *
 * <p>Parsing is per-vehicle and per-field defensive: a bad {@code tubes} value logs the vehicle
 * id and the field name, falls back to that field's default and lets every other entry load.
 * One typo can no longer cost you the rest of the table.
 */
public final class DefenseLayouts {

    public static final String FILE_NAME = "sbw_defense-vehicle-layouts.json";

    /**
     * Current table schema. 3 = vehicle ids use the {@code zzyvehicles} namespace that the VLS
     * addon adopted in 1.0.0b; older files still say {@code sbw_vls} and are rewritten on load.
     */
    static final int SCHEMA = 3;

    /** The VLS addon merged every namespace into {@code zzyvehicles} in 1.0.0b. */
    private static final String VLS_OLD_NS = "sbw_vls:";
    private static final String VLS_NEW_NS = "zzyvehicles:";

    /** Order of the five ERA faces, matching {@link VehicleDefense} Face ordinals. */
    public static final String[] ERA_FACES = {"front", "back", "left", "right", "top"};

    /**
     * One launcher group.
     *
     * @param azimuth hull-relative bearing of the tube cluster
     * @param tubes   rounds carried (0 = blind sector)
     * @param mount   optional launch point in blocks {@code [x(right), y(up), z(forward)]}
     */
    public record Sector(float azimuth, int tubes, float[] mount) {}

    /**
     * Sectors of one vehicle in file order (HUD bit mask follows this order) plus optional ERA
     * cell counts per face in {@link #ERA_FACES} order; {@code null} keeps the configured default.
     */
    public record Layout(List<Sector> sectors, int[] eraCells) {
        public int count() {
            return sectors.size();
        }

        /**
         * Index of the sector whose azimuth is closest to {@code relYaw}. Wedge boundaries end up
         * halfway between neighbouring tubes, so a sector with {@code tubes = 0} really is a blind
         * spot instead of silently borrowing the next tube.
         */
        public int sectorFor(double relYaw) {
            if (sectors.isEmpty()) return -1;
            int best = 0;
            double bestDelta = 361.0;
            for (int i = 0; i < sectors.size(); i++) {
                double d = Math.abs(delta(relYaw, sectors.get(i).azimuth()));
                if (d < bestDelta) {
                    bestDelta = d;
                    best = i;
                }
            }
            return best;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile Map<String, Layout> byVehicle = Map.of();
    private static volatile Layout fallback;

    /**
     * Entity type -&gt; resolved layout. Populated on first sight of a hull (a server tick path),
     * so the per-tick APS loop does not hit the registry and the string map again. Cleared
     * whenever the table is (re)loaded. {@code Optional} lets a hull without any entry be cached
     * as "none" instead of being re-resolved every tick.
     */
    private static final Map<EntityType<?>, Optional<Layout>> CACHE = new ConcurrentHashMap<>();

    // ── lifecycle ─────────────────────────────────────────────────────────

    /** Called once when a server starts: create the file on first run, then parse it. */
    public static void bootstrap(Logger logger) {
        try {
            Path path = file();
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                writeDefault(path);
                logger.info("Wrote default defensive layout table to {}", path);
            } else {
                migrateLegacy(path, logger);
            }
            reload(logger);
        } catch (Exception e) {
            logger.warn("Layout table unavailable, falling back to the config values", e);
            byVehicle = Map.of();
            fallback = null;
            CACHE.clear();
        }
    }

    private static void writeDefault(Path path) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(DEFAULT_FILE);
        }
    }

    /**
     * Schema 0 was 1.5.x: no {@code "_schema"} at all, and a four-sector {@code default} that
     * handed a hard-kill system to every vehicle in the pack - replaced by the template.
     *
     * <p>Schema 1/2 are structurally fine but still name hulls {@code sbw_vls:*}. VLS 1.0.0b
     * merged everything into {@code zzyvehicles}, so those keys are rewritten in place (custom
     * entries and all) instead of being thrown away; the original file is kept as {@code .bak}.
     */
    private static void migrateLegacy(Path path, Logger logger) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int schema = root.has("_schema") ? root.get("_schema").getAsInt() : 0;
            if (schema >= SCHEMA) return;

            Path backup = path.resolveSibling(FILE_NAME + ".bak");
            Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            if (schema == 0) {
                writeDefault(path);
                logger.info("Upgraded legacy layout table to schema {} (backup: {}); unlisted vehicles no longer carry APS",
                        SCHEMA, backup);
                return;
            }

            int renamed = 0;
            JsonElement vehiclesEl = root.get("vehicles");
            if (vehiclesEl != null && vehiclesEl.isJsonObject()) {
                JsonObject rebuilt = new JsonObject();
                for (Map.Entry<String, JsonElement> e : vehiclesEl.getAsJsonObject().entrySet()) {
                    String key = e.getKey();
                    String canon = canonical(key);
                    if (!canon.equals(key)) renamed++;
                    rebuilt.add(canon, e.getValue());
                }
                root.add("vehicles", rebuilt);
            }
            root.addProperty("_schema", SCHEMA);
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            logger.info("Migrated layout table to schema {} (backup: {}), {} vehicle id(s) moved to zzyvehicles",
                    SCHEMA, backup, renamed);
        } catch (Exception e) {
            logger.warn("Could not migrate {}: {}", FILE_NAME, e.getMessage());
        }
    }

    /**
     * Maps a legacy {@code sbw_vls:} id onto the {@code zzyvehicles:} namespace VLS has used since
     * 1.0.0b. Everything else (vanilla SBW hulls, other addons) passes through untouched.
     */
    static String canonical(String id) {
        return id.startsWith(VLS_OLD_NS) ? VLS_NEW_NS + id.substring(VLS_OLD_NS.length()) : id;
    }

    /** The same hull under the other namespace - used to match a config entry written by hand. */
    static String alias(String id) {
        if (id.startsWith(VLS_OLD_NS)) return VLS_NEW_NS + id.substring(VLS_OLD_NS.length());
        if (id.startsWith(VLS_NEW_NS)) return VLS_OLD_NS + id.substring(VLS_NEW_NS.length());
        return id;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private static void reload(Logger logger) {
        String text;
        try {
            text = Files.readString(file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not read {}: {}", FILE_NAME, e.getMessage());
            byVehicle = Map.of();
            fallback = null;
            CACHE.clear();
            return;
        }
        Parsed parsed = parse(text, logger);
        byVehicle = parsed.vehicles();
        fallback = parsed.fallback();
        CACHE.clear();   // cached verdicts were resolved against the previous table
        logger.info("Defensive layouts loaded: {} vehicle entries, default {}",
                byVehicle.size(), fallback == null ? "off" : fallback.count() + " sectors");
    }

    /** Result of one table parse: per-vehicle entries plus the {@code default} fallback. */
    record Parsed(Map<String, Layout> vehicles, Layout fallback) {}

    /** Test-friendly entry point: parses without a logger. */
    static Parsed parse(String json) {
        return parse(json, null);
    }

    /**
     * Parses the layout table. A malformed <em>vehicle</em> entry is skipped on its own; a
     * malformed <em>field</em> falls back to its default. Either way the rest of the table loads.
     */
    static Parsed parse(String json, Logger log) {
        Map<String, Layout> map = new HashMap<>();
        Layout def = null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("default")) {
                try {
                    def = parseLayout(root.get("default"), "default", log);
                } catch (RuntimeException e) {
                    warn(log, "'default' layout skipped: {}", e.getMessage());
                }
            }

            JsonElement vehicles = root.get("vehicles");
            if (vehicles != null && vehicles.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : vehicles.getAsJsonObject().entrySet()) {
                    String key = e.getKey();
                    if (key.startsWith("_")) continue;   // comment keys
                    try {
                        Layout l = parseLayout(e.getValue(), key, log);
                        if (l != null && (l.count() > 0 || l.eraCells() != null)) {
                            map.put(canonical(key), l);   // sbw_vls:* -> zzyvehicles:*
                        }
                    } catch (RuntimeException ex) {
                        // one broken vehicle must never take the following entries with it
                        warn(log, "vehicle '{}' skipped: {}", key, ex.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            // unparsable file: fall back to the uniform config rather than crashing the server
            warn(log, "{} is not valid JSON, using config defaults: {}", FILE_NAME, e.getMessage());
        }
        return new Parsed(Map.copyOf(map), def);
    }

    // ── queries ───────────────────────────────────────────────────────────

    /** Layout for this hull, or {@code null} when the file defines neither an entry nor a default. */
    public static Layout forVehicle(VehicleEntity v) {
        return CACHE.computeIfAbsent(v.getType(), DefenseLayouts::resolve).orElse(null);
    }

    private static Optional<Layout> resolve(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return Optional.ofNullable(id == null ? fallback : forId(id.toString()));
    }

    public static Layout forId(String entityId) {
        Layout l = byVehicle.get(canonical(entityId));
        return l != null ? l : fallback;
    }

    // ── parsing ───────────────────────────────────────────────────────────

    private static Layout parseLayout(JsonElement element, String owner, Logger log) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();

        List<Sector> sectors = new ArrayList<>();
        JsonElement secs = obj.get("sectors");
        if (secs != null && secs.isJsonArray()) {
            int i = 0;
            for (JsonElement se : secs.getAsJsonArray()) {
                String ctx = owner + ".sectors[" + i + "]";
                i++;
                if (!se.isJsonObject()) {
                    warn(log, "{}: not an object, ignored", ctx);
                    continue;
                }
                JsonObject so = se.getAsJsonObject();
                try {
                    float azimuth = readFloat(so, "azimuth", 0.0F, ctx, log);
                    int tubes = Math.max(0, Math.min(8, readInt(so, "tubes", 1, ctx, log)));
                    sectors.add(new Sector(normalise(azimuth), tubes, readMount(so, ctx, log)));
                } catch (RuntimeException ex) {
                    warn(log, "{}: sector skipped ({})", ctx, ex.getMessage());
                }
            }
        }

        int[] era = null;
        JsonElement eraEl = obj.get("era");
        if (eraEl != null && eraEl.isJsonObject()) {
            JsonObject eo = eraEl.getAsJsonObject();
            era = new int[ERA_FACES.length];
            for (int i = 0; i < ERA_FACES.length; i++) {
                era[i] = Math.max(0, Math.min(64,
                        readInt(eo, ERA_FACES[i], 0, owner + ".era", log)));
            }
        }

        if (sectors.isEmpty() && era == null) return null;
        return new Layout(List.copyOf(sectors), era);
    }

    /** {@code [right, up, forward]} in blocks; a broken array is ignored (hull-centre launch). */
    private static float[] readMount(JsonObject so, String ctx, Logger log) {
        JsonElement mo = so.get("mount");
        if (mo == null || mo.isJsonNull()) return null;
        if (!mo.isJsonArray()) {
            warn(log, "{}: 'mount' is not an array, ignored", ctx);
            return null;
        }
        JsonArray ma = mo.getAsJsonArray();
        if (ma.size() < 3) {
            warn(log, "{}: 'mount' needs 3 values, ignored", ctx);
            return null;
        }
        float[] mount = new float[3];
        for (int k = 0; k < 3; k++) {
            if (!isNumber(ma.get(k))) {
                warn(log, "{}: 'mount[{}]' is not a number, ignored", ctx, k);
                return null;
            }
            mount[k] = ma.get(k).getAsFloat();
        }
        return mount;
    }

    private static float readFloat(JsonObject o, String field, float def, String ctx, Logger log) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return def;
        if (!isNumber(e)) {
            warn(log, "{}: '{}' is not a number ({}), using {}", ctx, field, e, def);
            return def;
        }
        return e.getAsFloat();
    }

    private static int readInt(JsonObject o, String field, int def, String ctx, Logger log) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return def;
        if (!isNumber(e)) {
            warn(log, "{}: '{}' is not a number ({}), using {}", ctx, field, e, def);
            return def;
        }
        return e.getAsInt();
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static void warn(Logger log, String message, Object... args) {
        if (log != null) log.warn(message, args);
    }

    /** Normalises any angle into [-180, 180). */
    private static float normalise(float degrees) {
        return ((degrees % 360.0F) + 540.0F) % 360.0F - 180.0F;
    }

    /** Signed shortest angular distance {@code a-b}, in [-180, 180). */
    private static double delta(double a, double b) {
        return ((a - b) % 360.0 + 540.0) % 360.0 - 180.0;
    }

    // ── default file ──────────────────────────────────────────────────────

    static final String DEFAULT_FILE = """
            {
              "_schema": 3,
              "_readme": [
                "主防（APS）发射管位置 + 爆反（ERA）覆盖表 —— 改完重启服务器或重进世界生效",
                "",
                "载具 ID 用 zzyvehicles:<文件名>（VLS 附属 1.0.0b 起合并命名空间）；",
                "   旧版写的 sbw_vls:<文件名> 在读取时自动按同名映射，老的配置文件也会被改写并留 .bak。",
                "azimuth: 相对车头的方位角（度，顺时针为正）",
                "   0 = 车头    90 = 车体右侧    180 / -180 = 车尾    -90 / 270 = 车体左侧",
                "tubes:   该扇区发射管数；填 0 表示这个方向是盲区（永远不会往那边开火）",
                "mount:   可选，发射点相对车体原点的偏移（格），[右, 上, 前]；不写就从车体中心 y+1.2 发射",
                "era:     可选，五个面的爆反格数 front/back/left/right/top，0 = 该面没有爆反；",
                "         整段 era 都写 0 等于这台车没有爆反（HUD 不显示爆反行）",
                "",
                "sectors 写成空数组 [] = 这台车没有主防（HUD 不显示 APS 行）",
                "**没有列在 vehicles 里的车 = 没有主防**（硬杀只给表里这些车装）",
                "想要给所有未列出车型一个兜底，自己加一段 \\"default\\": { \\"sectors\\": [...] } 即可。",
                "命中判定：来袭弹按方位角分配给「方位最接近」的扇区；扇区边界 = 相邻角中点。",
                "",
                "下面的条目按「现实里真的装了这套东西」的车型预填，mount 取自各车模型的",
                "TurretPos / OBB 尺寸；模型里带 APS/ERA 骨骼的车（vt4a1、vt4b、ztz99bh 等）以骨骼为准。",
                "没有 prefill 的车仍然吃配置里的爆反默认值（defense.era.cellsPerFace）。",
                "某个车型写错（比如 tubes 填成文字）只跳过这一个字段并写日志，不影响其他车型。"
              ],
              "vehicles": {
                "_comment_aps_gl5": "GL5 硬杀：炮塔前侧两组发射器，尾部无覆盖",
                "zzyvehicles:vt4a1": {
                  "sectors": [
                    { "azimuth": -40, "tubes": 2, "mount": [-0.30, 0.95, 0.35] },
                    { "azimuth": 40, "tubes": 2, "mount": [0.30, 0.95, 0.35] },
                    { "azimuth": 180, "tubes": 0 }
                  ],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 6, "back": 0 }
                },
                "zzyvehicles:vt4b": {
                  "sectors": [
                    { "azimuth": -40, "tubes": 2, "mount": [-0.28, 0.82, 0.28] },
                    { "azimuth": 40, "tubes": 2, "mount": [0.28, 0.82, 0.28] },
                    { "azimuth": 180, "tubes": 0 }
                  ],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 6, "back": 0 }
                },
                "zzyvehicles:ztz99bh": {
                  "sectors": [
                    { "azimuth": -45, "tubes": 2 },
                    { "azimuth": 45, "tubes": 2 },
                    { "azimuth": 180, "tubes": 0 }
                  ],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "_comment_aps_trophy": "Trophy：炮塔两侧各一具发射器，四部雷达覆盖全向",
                "zzyvehicles:m1a2sepv2": {
                  "sectors": [
                    { "azimuth": 90, "tubes": 2, "mount": [0.52, 0.90, 0.45] },
                    { "azimuth": -90, "tubes": 2, "mount": [-0.52, 0.90, 0.45] }
                  ],
                  "era": { "front": 8, "left": 10, "right": 10, "top": 8, "back": 4 }
                },
                "zzyvehicles:abrams_x": {
                  "sectors": [
                    { "azimuth": 90, "tubes": 2 },
                    { "azimuth": -90, "tubes": 2 }
                  ],
                  "era": { "front": 0, "back": 0, "left": 0, "right": 0, "top": 0 }
                },

                "_comment_era_relict": "苏系 Relikt / Kontakt，尾部与发动机舱盖无爆反",
                "zzyvehicles:t72_b3m": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:t72b": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:t72b3": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:t80b": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:t90_msa": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 10, "back": 0 }
                },
                "zzyvehicles:t90_msa_keep": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 10, "back": 0 }
                },
                "zzyvehicles:t90mh": {
                  "sectors": [],
                  "era": { "front": 12, "left": 12, "right": 12, "top": 10, "back": 0 }
                },
                "zzyvehicles:project640": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 10, "back": 4 }
                },
                "zzyvehicles:bmpt72": {
                  "sectors": [],
                  "era": { "front": 10, "left": 8, "right": 8, "top": 4, "back": 0 }
                },
                "_comment_era_heavy": "BVM / Oplot / K1 这类重爆反车：侧裙与炮塔顶也有块",
                "zzyvehicles:t_80bvm": {
                  "sectors": [],
                  "era": { "front": 12, "left": 12, "right": 12, "top": 10, "back": 0 }
                },
                "zzyvehicles:t_80u": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:oplot": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:k1a1": {
                  "sectors": [],
                  "era": { "front": 10, "left": 8, "right": 8, "top": 6, "back": 0 }
                },

                "_comment_era_fy4": "国产 FY-4 反应装甲",
                "zzyvehicles:ztz96a": {
                  "sectors": [],
                  "era": { "front": 10, "left": 8, "right": 8, "top": 6, "back": 0 }
                },
                "zzyvehicles:ztz99a": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:ztz99ah": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "zzyvehicles:ztq15": {
                  "sectors": [],
                  "era": { "front": 10, "left": 8, "right": 8, "top": 4, "back": 0 }
                },
                "zzyvehicles:m1a2sepv1": {
                  "sectors": [],
                  "era": { "front": 8, "left": 10, "right": 10, "top": 6, "back": 0 }
                },
                "zzyvehicles:m1a1hc": {
                  "sectors": [],
                  "era": { "front": 8, "left": 10, "right": 10, "top": 6, "back": 0 }
                }
              }
            }
            """;

    private DefenseLayouts() {}
}
