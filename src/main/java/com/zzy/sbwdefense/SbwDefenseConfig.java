package com.zzy.sbwdefense;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config for heavy ERA + hard-kill APS.
 *
 * Everything lives under the {@code defense.*} path so an existing
 * {@code sbw_radar-server.toml} can be pasted over with minimal editing.
 */
public final class SbwDefenseConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> DEFENSE_VEHICLES = BUILDER
            .comment("Vehicles that get ERA / APS (entity ids, or \"*\" for every vehicle)",
                    "Format: namespace:path, e.g. superbwarfare:t_90m")
            .defineList("defense.vehicles", java.util.List.of("*"), o -> o instanceof String);

    // ── ERA ───────────────────────────────────────────────────────────────

    public static final ModConfigSpec.BooleanValue ERA_ENABLE = BUILDER
            .comment("Enable per-block heavy ERA absorption")
            .define("defense.era.enable", true);

    public static final ModConfigSpec.IntValue ERA_CELLS_PER_FACE = BUILDER
            .comment("ERA grid cells per face (front/back/left/right/top)")
            .defineInRange("defense.era.cellsPerFace", 12, 1, 64);

    public static final ModConfigSpec.IntValue ERA_BLOCKS_PER_CELL = BUILDER
            .comment("Blocks stacked in one grid cell (a cell absorbs that many hits)")
            .defineInRange("defense.era.blocksPerCell", 1, 1, 4);

    public static final ModConfigSpec.DoubleValue ERA_INTERNAL_REDUCTION = BUILDER
            .comment("Damage reduction against Superb Warfare's own ammo (namespace superbwarfare)",
                    "0.8 = the round still does 20% of its damage; the block is spent either way")
            .defineInRange("defense.era.reduction.internal", 0.8, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue ERA_EXTERNAL_REDUCTION = BUILDER
            .comment("Damage reduction against every other ammo namespace (addons, other mods)",
                    "1.0 = the round is fully stopped")
            .defineInRange("defense.era.reduction.external", 1.0, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue ERA_KEEP_EXPLOSION = BUILDER
            .comment("When a hit is fully blocked (reduction 1.0) the warhead still detonates outside",
                    "the plate, so the blast keeps its original damage.",
                    "false = swallow the explosion too (pure no-damage block)")
            .define("defense.era.keepExplosion", true);

    public static final ModConfigSpec.DoubleValue EXTERNAL_BLAST_CAP = BUILDER
            .comment("Hard cap on the explosion damage of an EXTERNAL warhead (namespace != superbwarfare).",
                    "Applies both when ERA stops the round on the hull and when the APS takes it out",
                    "mid-air, so a point-blank cook-off can never wreck the vehicle it was fired at.",
                    "10.0 = at most 10 damage at the blast centre; set high to disable the cap")
            .defineInRange("defense.externalBlastCap", 10.0, 0.0, 500.0);

    public static final ModConfigSpec.BooleanValue ERA_ONLY_EXPLOSIVE = BUILDER
            .comment("Only chemical-energy projectiles (missiles / rockets / shells) are absorbed;",
                    "plain arrows and bullets pass through. Turn off to absorb every projectile.")
            .define("defense.era.onlyExplosive", true);

    // ── APS ───────────────────────────────────────────────────────────────

    public static final ModConfigSpec.BooleanValue APS_ENABLE = BUILDER
            .comment("Enable hard-kill APS: launches a real interceptor at incoming projectiles",
                    "Only vehicles listed in config/sbw_defense-vehicle-layouts.json carry tubes -",
                    "an unlisted hull has no APS at all.")
            .define("defense.aps.enable", true);

    public static final ModConfigSpec.IntValue APS_RELOAD_TICKS = BUILDER
            .comment("Reload time per sector in ticks")
            .defineInRange("defense.aps.reloadTicks", 100, 10, 1200);

    public static final ModConfigSpec.DoubleValue APS_RADIUS = BUILDER
            .comment("Detection radius: incoming projectiles inside this range are engaged")
            .defineInRange("defense.aps.radius", 48.0, 8.0, 128.0);

    public static final ModConfigSpec.DoubleValue APS_KILL_RADIUS = BUILDER
            .comment("Distance at which the interceptor detonates on the threat")
            .defineInRange("defense.aps.killRadius", 3.0, 1.0, 12.0);

    public static final ModConfigSpec.DoubleValue APS_MIN_SPEED = BUILDER
            .comment("Minimum closing speed (blocks/tick) for a projectile to count as a threat",
                    "Keeps dropped items and slow lobs from draining the tubes")
            .defineInRange("defense.aps.minClosingSpeed", 0.35, 0.0, 4.0);

    public static final ModConfigSpec.ConfigValue<String> APS_INTERCEPTOR = BUILDER
            .comment("Entity used as the interceptor body")
            .define("defense.aps.interceptor", "superbwarfare:fim_92_missile");

    public static final ModConfigSpec.BooleanValue APS_WARHEAD_DETONATES = BUILDER
            .comment("An intercepted projectile still cooks off where it was met (same as the ERA",
                    "fully-blocked case). false = the interceptor simply deletes it")
            .define("defense.aps.warheadDetonates", true);

    // ── HUD sync ──────────────────────────────────────────────────────────

    public static final ModConfigSpec.IntValue SYNC_INTERVAL = BUILDER
            .comment("How often (ticks) the ERA / APS state is pushed to the riding player's HUD")
            .defineInRange("defense.syncInterval", 5, 1, 40);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SbwDefenseConfig() {}
}
