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
 */
public final class DefenseLayouts {

    public static final String FILE_NAME = "sbw_defense-vehicle-layouts.json";

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
        }
    }

    private static void writeDefault(Path path) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(DEFAULT_FILE);
        }
    }

    /**
     * 1.5.x shipped the same file without {@code "_schema"}: it still had a four-sector
     * {@code default}, which handed a hard-kill system to every vehicle in the pack. Those files
     * are replaced by the 1.6 template (old one kept next to it as {@code .bak}).
     */
    private static void migrateLegacy(Path path, Logger logger) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.has("_schema")) return;
            Path backup = path.resolveSibling(FILE_NAME + ".bak");
            Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            writeDefault(path);
            logger.info("Upgraded legacy layout table (backup: {}); unlisted vehicles no longer carry APS", backup);
        } catch (Exception e) {
            logger.warn("Could not migrate {}: {}", FILE_NAME, e.getMessage());
        }
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
            return;
        }
        Parsed parsed = parse(text);
        byVehicle = parsed.vehicles();
        fallback = parsed.fallback();
        logger.info("Defensive layouts loaded: {} vehicle entries, default {}",
                byVehicle.size(), fallback == null ? "off" : fallback.count() + " sectors");
    }

    /** Result of one table parse: per-vehicle entries plus the {@code default} fallback. */
    record Parsed(Map<String, Layout> vehicles, Layout fallback) {}

    /** Parses the layout table. Malformed entries are skipped instead of failing the whole file. */
    static Parsed parse(String json) {
        Map<String, Layout> map = new HashMap<>();
        Layout def = null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("default")) def = parseLayout(root.get("default"));
            if (root.has("vehicles") && root.get("vehicles").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("vehicles").entrySet()) {
                    if (e.getKey().startsWith("_")) continue;
                    Layout l = parseLayout(e.getValue());
                    if (l != null && (l.count() > 0 || l.eraCells() != null)) map.put(e.getKey(), l);
                }
            }
        } catch (RuntimeException e) {
            // unparsable file: fall back to the uniform config rather than crashing the server
        }
        return new Parsed(Map.copyOf(map), def);
    }

    // ── queries ───────────────────────────────────────────────────────────

    /** Layout for this hull, or {@code null} when the file defines neither an entry nor a default. */
    public static Layout forVehicle(VehicleEntity v) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(v.getType());
        if (id == null) return fallback;
        return forId(id.toString());
    }

    public static Layout forId(String entityId) {
        Layout l = byVehicle.get(entityId);
        return l != null ? l : fallback;
    }

    // ── parsing ───────────────────────────────────────────────────────────

    private static Layout parseLayout(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();

        List<Sector> sectors = new ArrayList<>();
        JsonElement secs = obj.get("sectors");
        if (secs != null && secs.isJsonArray()) {
            for (JsonElement se : secs.getAsJsonArray()) {
                if (!se.isJsonObject()) continue;
                JsonObject so = se.getAsJsonObject();
                float azimuth = so.has("azimuth") ? so.get("azimuth").getAsFloat() : 0.0F;
                int tubes = so.has("tubes") ? Math.max(0, Math.min(8, so.get("tubes").getAsInt())) : 1;
                float[] mount = null;
                JsonElement mo = so.get("mount");
                if (mo != null && mo.isJsonArray() && mo.getAsJsonArray().size() >= 3) {
                    JsonArray ma = mo.getAsJsonArray();
                    mount = new float[]{
                            ma.get(0).getAsFloat(),
                            ma.get(1).getAsFloat(),
                            ma.get(2).getAsFloat()};
                }
                sectors.add(new Sector(normalise(azimuth), tubes, mount));
            }
        }

        int[] era = null;
        JsonElement eraEl = obj.get("era");
        if (eraEl != null && eraEl.isJsonObject()) {
            JsonObject eo = eraEl.getAsJsonObject();
            era = new int[ERA_FACES.length];
            for (int i = 0; i < ERA_FACES.length; i++) {
                era[i] = eo.has(ERA_FACES[i])
                        ? Math.max(0, Math.min(64, eo.get(ERA_FACES[i]).getAsInt()))
                        : 0;
            }
        }

        if (sectors.isEmpty() && era == null) return null;
        return new Layout(List.copyOf(sectors), era);
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
              "_schema": 2,
              "_readme": [
                "主防（APS）发射管位置 + 爆反（ERA）覆盖表 —— 改完重启服务器或重进世界生效",
                "",
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
                "没有 prefill 的车仍然吃配置里的爆反默认值（defense.era.cellsPerFace）。"
              ],
              "vehicles": {
                "_comment_aps_gl5": "GL5 硬杀：炮塔前侧两组发射器，尾部无覆盖",
                "sbw_vls:vt4a1": {
                  "sectors": [
                    { "azimuth": -40, "tubes": 2, "mount": [-0.30, 0.95, 0.35] },
                    { "azimuth": 40, "tubes": 2, "mount": [0.30, 0.95, 0.35] },
                    { "azimuth": 180, "tubes": 0 }
                  ],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 6, "back": 0 }
                },
                "sbw_vls:vt4b": {
                  "sectors": [
                    { "azimuth": -40, "tubes": 2, "mount": [-0.28, 0.82, 0.28] },
                    { "azimuth": 40, "tubes": 2, "mount": [0.28, 0.82, 0.28] },
                    { "azimuth": 180, "tubes": 0 }
                  ],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 6, "back": 0 }
                },
                "sbw_vls:ztz99bh": {
                  "sectors": [
                    { "azimuth": -45, "tubes": 2 },
                    { "azimuth": 45, "tubes": 2 },
                    { "azimuth": 180, "tubes": 0 }
                  ],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "_comment_aps_trophy": "Trophy：炮塔两侧各一具发射器，四部雷达覆盖全向",
                "sbw_vls:m1a2sepv2": {
                  "sectors": [
                    { "azimuth": 90, "tubes": 2, "mount": [0.52, 0.90, 0.45] },
                    { "azimuth": -90, "tubes": 2, "mount": [-0.52, 0.90, 0.45] }
                  ],
                  "era": { "front": 8, "left": 10, "right": 10, "top": 8, "back": 4 }
                },
                "sbw_vls:abrams_x": {
                  "sectors": [
                    { "azimuth": 90, "tubes": 2 },
                    { "azimuth": -90, "tubes": 2 }
                  ],
                  "era": { "front": 0, "back": 0, "left": 0, "right": 0, "top": 0 }
                },

                "_comment_era_relict": "苏系 Relikt / Kontakt，尾部与发动机舱盖无爆反",
                "sbw_vls:t72b": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "sbw_vls:t72b3": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "sbw_vls:t80b": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "sbw_vls:t90_msa": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 10, "back": 0 }
                },
                "sbw_vls:t90_msa_keep": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 10, "back": 0 }
                },
                "sbw_vls:t90mh": {
                  "sectors": [],
                  "era": { "front": 12, "left": 12, "right": 12, "top": 10, "back": 0 }
                },
                "sbw_vls:project640": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 10, "back": 4 }
                },
                "sbw_vls:bmpt72": {
                  "sectors": [],
                  "era": { "front": 10, "left": 8, "right": 8, "top": 4, "back": 0 }
                },

                "_comment_era_fy4": "国产 FY-4 反应装甲",
                "sbw_vls:ztz96a": {
                  "sectors": [],
                  "era": { "front": 10, "left": 8, "right": 8, "top": 6, "back": 0 }
                },
                "sbw_vls:ztz99a": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "sbw_vls:ztz99ah": {
                  "sectors": [],
                  "era": { "front": 12, "left": 10, "right": 10, "top": 8, "back": 0 }
                },
                "sbw_vls:ztq15": {
                  "sectors": [],
                  "era": { "front": 10, "left": 8, "right": 8, "top": 4, "back": 0 }
                },
                "sbw_vls:m1a1hc": {
                  "sectors": [],
                  "era": { "front": 8, "left": 10, "right": 10, "top": 6, "back": 0 }
                }
              }
            }
            """;

    private DefenseLayouts() {}
}
