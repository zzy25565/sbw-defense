package com.zzy.sbwdefense;

import net.minecraft.client.Minecraft;

/**
 * Client-side mirror of the defensive state of the hull we are sitting in.
 * The state goes stale after {@link #STALE_TICKS} ticks without an update, so a destroyed
 * vehicle can never leave a floating panel behind.
 *
 * <p>APS events carry the vehicle id: a neighbouring tank firing must not flash anything on
 * this HUD, and a launch ("the tube is empty now") is shown differently from a confirmed kill.
 */
public final class ClientDefenseState {

    private static final long STALE_TICKS = 60L;
    private static final long FLASH_TICKS = 30L;

    private static DefensePayloads.State state;
    private static long stamp;
    private static long flashStamp = Long.MIN_VALUE;
    private static int flashSector = -1;
    private static int flashVehicle = -1;
    private static boolean flashKill;

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
        flashVehicle = -1;
        flashKill = false;
    }

    /**
     * @param vehicleId hull that produced the event - events from other hulls are ignored
     * @param killed    {@code true} for a confirmed interception, {@code false} for a launch
     */
    public static void flash(int vehicleId, int sector, boolean killed) {
        flashVehicle = vehicleId;
        flashSector = sector;
        flashKill = killed;
        flashStamp = now();
    }

    /** 0..1 fade of the APS banner for {@code vehicleId}, 0 when idle or another hull fired. */
    public static float flashLevel(int vehicleId) {
        if (vehicleId != flashVehicle) return 0.0F;
        long d = now() - flashStamp;
        if (d < 0L || d > FLASH_TICKS) return 0.0F;
        return 1.0F - (float) d / (float) FLASH_TICKS;
    }

    /** {@code true} when the current flash is a confirmed kill, {@code false} for a launch. */
    public static boolean flashKilled(int vehicleId) {
        return vehicleId == flashVehicle && flashKill;
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
