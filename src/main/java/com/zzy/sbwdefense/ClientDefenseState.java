package com.zzy.sbwdefense;

import net.minecraft.client.Minecraft;

/**
 * Client-side mirror of the defensive state of the hull we are sitting in.
 * The state goes stale after {@link #STALE_TICKS} ticks without an update, so a destroyed
 * vehicle can never leave a floating panel behind.
 */
public final class ClientDefenseState {

    private static final long STALE_TICKS = 60L;
    private static final long FLASH_TICKS = 30L;

    private static DefensePayloads.State state;
    private static long stamp;
    private static long flashStamp = Long.MIN_VALUE;
    private static int flashSector = -1;

    public static void set(DefensePayloads.State s) {
        state = s;
        stamp = now();
    }

    /** State for {@code vehicleId}, or {@code null} when it belongs to another hull / went stale. */
    public static DefensePayloads.State get(int vehicleId) {
        if (state == null || state.vehicleId() != vehicleId) return null;
        if (now() - stamp > STALE_TICKS) return null;
        return state;
    }

    public static void clear() {
        state = null;
        stamp = 0L;
        flashStamp = Long.MIN_VALUE;
    }

    public static void flash(int sector) {
        flashSector = sector;
        flashStamp = now();
    }

    /** 0..1 fade of the "INTERCEPT" banner, 0 when idle. */
    public static float flashLevel() {
        long d = now() - flashStamp;
        if (d < 0L || d > FLASH_TICKS) return 0.0F;
        return 1.0F - (float) d / (float) FLASH_TICKS;
    }

    public static int flashSector() {
        return flashSector;
    }

    private static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }

    private ClientDefenseState() {}
}
