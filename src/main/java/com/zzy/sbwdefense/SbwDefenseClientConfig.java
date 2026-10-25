package com.zzy.sbwdefense;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side config for the ERA / APS panel. */
public final class SbwDefenseClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HUD_ENABLE = BUILDER
            .comment("Show the ERA / APS panel while riding a protected vehicle")
            .define("hud.enable", true);

    public static final ModConfigSpec.IntValue HUD_OFFSET_X = BUILDER
            .comment("Panel offset from the top-left corner (pixels, + = right)")
            .defineInRange("hud.offsetX", 0, -400, 400);

    public static final ModConfigSpec.IntValue HUD_OFFSET_Y = BUILDER
            .comment("Panel offset from the top edge (pixels, + = down)")
            .defineInRange("hud.offsetY", 0, -400, 400);

    public static final ModConfigSpec.BooleanValue HUD_SHOW_LABELS = BUILDER
            .comment("Draw the ERA / APS text labels (turn off for an icon-only panel)")
            .define("hud.labels", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SbwDefenseClientConfig() {}
}
