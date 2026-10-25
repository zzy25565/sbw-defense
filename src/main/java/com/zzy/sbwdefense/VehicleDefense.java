package com.zzy.sbwdefense;

import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent;
import com.atsuishio.superbwarfare.entity.projectile.DestroyableProjectile;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.atsuishio.superbwarfare.entity.projectile.IBulletProperties;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Heavy ERA (per block) + hard-kill APS (real interceptor launch) for SBW vehicles.
 *
 * <p>ERA - the hull is divided into faces (front / back / left / right / top), each face into
 * a small grid. Every grid cell holds {@code blocksPerCell} reactive blocks. A hit lands in a
 * cell; while that cell still has a block the round is blunted and the block is spent: 100 %
 * reduction cancels the hit outright, anything less scales the projectile's own damage and
 * explosion values so Superb Warfare's normal armour / angle pipeline settles the rest.
 * Reduction is picked per ammo namespace - {@code superbwarfare} (stock rounds) vs everything
 * else (addons, other mods). No model topology is needed: the cell is derived from the hit
 * point projected onto the face, so it also works for addon vehicles.
 *
 * <p>APS - every tick a manned vehicle looks for incoming projectiles (closing velocity &gt; 0)
 * inside its radius, picks the most urgent one covered by a sector that still has a tube, and
 * launches a real interceptor entity at it. The interceptor homes on the threat; within a few
 * blocks it detonates and kills the threat.
 *
 * <p>State is keyed by vehicle UUID (not the transient entity id) so recycled ids can never
 * inherit another tank's spent armour, and it is dropped when the vehicle leaves the level.
 */
public final class VehicleDefense {

    // ── ERA ────────────────────────────────────────────────────────────────

    private enum Face { FRONT, BACK, LEFT, RIGHT, TOP }

    private static final class EraState {
        /** blocks per face cell, keyed {@code FACE:index} */
        final Map<String, Integer> blocks = new HashMap<>();
        /** cell count of each face, in Face ordinal order - a layout may leave a face bare */
        final int[] cells;
        final int blocksPerCell;
        final int total;
        int left;

        EraState(int[] cellsPerFace, int blocksPerCell) {
            cells = cellsPerFace.clone();
            this.blocksPerCell = blocksPerCell;
            int sum = 0;
            Face[] faces = Face.values();
            for (int f = 0; f < faces.length; f++) {
                for (int i = 0; i < cells[f]; i++) {
                    blocks.put(faces[f].name() + ":" + i, blocksPerCell);
                    sum += blocksPerCell;
                }
            }
            total = sum;
            left = total;
        }

        int percent() {
            return total == 0 ? -1 : (int) Math.round(100.0 * left / total);
        }
    }

    // ── APS ────────────────────────────────────────────────────────────────

    private static final class ApsState {
        /** capacity per sector, straight from the vehicle's layout (0 = blind spot) */
        final int[] max;
        final int[] tubes;
        final int[] reload;

        ApsState(int[] maxTubes) {
            max = maxTubes.clone();
            tubes = maxTubes.clone();
            reload = new int[maxTubes.length];
        }

        int mask() {
            int mask = 0;
            for (int i = 0; i < tubes.length && i < 31; i++) {
                if (tubes[i] > 0) mask |= (1 << i);
            }
            return mask;
        }
    }

    private static final Map<UUID, EraState> ERA = new HashMap<>();
    private static final Map<UUID, ApsState> APS = new HashMap<>();
    /** interceptor entity -> threat entity */
    private static final Map<Entity, Entity> INTERCEPTORS = new HashMap<>();

    /** Registry-path keywords treated as chemical-energy / warhead threats. */
    private static final String[] WARHEAD_HINTS = {
            "shell", "missile", "rocket", "grenade", "bomb", "rpg", "torpedo",
            "cannon", "apfsds", "heat", "hesh", "atgm"
    };

    // ── ERA hit handling ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onProjectileHit(ProjectileHitEvent.HitEntity event) {
        if (!SbwDefenseConfig.ERA_ENABLE.get()) return;
        if (!(event.getTarget() instanceof VehicleEntity vehicle) || vehicle.isWreck()) return;
        if (!isCovered(vehicle)) return;
        if (SbwDefenseConfig.ERA_ONLY_EXPLOSIVE.get() && !isWarhead(event.getProjectile())) return;

        EraState st = era(vehicle);
        if (st == null || st.left <= 0) return;

        Face face = faceOf(vehicle, event.getHitVec());
        int cells = st.cells[face.ordinal()];
        if (cells <= 0) return;                  // this face carries no ERA at all
        int cell = cellOf(vehicle, face, event.getHitVec(), cells);
        String key = face.name() + ":" + cell;

        // if this cell is empty look at the neighbours (a hit right on a seam should still count)
        String use = null;
        if (st.blocks.getOrDefault(key, 0) > 0) {
            use = key;
        } else {
            for (int d = -1; d <= 1 && use == null; d++) {
                String alt = face.name() + ":" + Math.floorMod(cell + d, cells);
                if (st.blocks.getOrDefault(alt, 0) > 0) use = alt;
            }
        }
        if (use == null) return; // that face is stripped: normal penetration

        st.blocks.put(use, st.blocks.get(use) - 1);
        st.left--;

        // A block is spent either way. What differs is how much of the round survives:
        //  * external ammo -> the hit itself is fully stopped (damage = 0) but the warhead still
        //                     cooks off outside the plate; its blast is capped so a point-blank
        //                     cook-off cannot wreck the hull.
        //  * internal ammo -> scale the projectile's own damage / explosion fields and let SBW's
        //                     normal pipeline run, so armour, impact angle and part damage apply.
        float reduction = reductionFor(event.getProjectile());
        if (event.getProjectile() instanceof IBulletProperties warhead) {
            float keep = 1.0F - reduction;
            warhead.setDamage(warhead.getDamage() * keep);
            if (isExternalAmmo(event.getProjectile())) {
                if (SbwDefenseConfig.ERA_KEEP_EXPLOSION.get()) {
                    capExternalBlast(event.getProjectile(), warhead);
                } else {
                    warhead.setExplosionDamage(0.0F);
                }
            } else if (keep > 0.001F) {
                // 部分减伤：直击和爆炸一起按比例削
                warhead.setExplosionDamage(warhead.getExplosionDamage() * keep);
            } else if (!SbwDefenseConfig.ERA_KEEP_EXPLOSION.get()) {
                // 完全挡下且关掉"保留爆炸"：连爆炸伤害一起抹掉，纯绝对防御
                warhead.setExplosionDamage(0.0F);
            }
            reaction(vehicle, event.getHitVec(), Math.max(0.3F, keep));
        } else {
            event.setCanceled(true);   // 缩放不到战斗部数值：只能整发取消
            reaction(vehicle, event.getHitVec(), 1.0F);
        }
    }

    /** External ammo = anything not registered under the {@code superbwarfare} namespace. */
    private static boolean isExternalAmmo(Entity projectile) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        return id == null || !"superbwarfare".equals(id.getNamespace());
    }

    /**
     * External warheads detonate, but never harder than {@code defense.externalBlastCap}.
     * Used both when ERA blocks the hit and when the APS takes the round out mid-air.
     */
    private static void capExternalBlast(Entity projectile, IBulletProperties warhead) {
        if (!isExternalAmmo(projectile)) return;
        float cap = (float) (double) SbwDefenseConfig.EXTERNAL_BLAST_CAP.get();
        if (warhead.getExplosionDamage() > cap) warhead.setExplosionDamage(cap);
    }

    /**
     * ERA reduction for this round: Superb Warfare's own ammo ({@code superbwarfare:*}) uses the
     * internal figure, every other namespace (addons, other mods) the external one.
     */
    private static float reductionFor(Entity projectile) {
        double v = isExternalAmmo(projectile)
                ? SbwDefenseConfig.ERA_EXTERNAL_REDUCTION.get()
                : SbwDefenseConfig.ERA_INTERNAL_REDUCTION.get();
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    /** Sparks + smoke + boom on the plate; {@code strength} scales the burst. */
    private static void reaction(VehicleEntity vehicle, Vec3 hit, float strength) {
        if (!(vehicle.level() instanceof ServerLevel level)) return;
        int crit = Math.max(6, Math.round(25 * strength));
        int smoke = Math.max(3, Math.round(8 * strength));
        level.sendParticles(ParticleTypes.CRIT, hit.x, hit.y, hit.z, crit, 0.6, 0.6, 0.6, 0.35);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, hit.x, hit.y, hit.z, smoke, 0.4, 0.4, 0.4, 0.02);
        level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 0.45F + 0.25F * strength, 1.6F);
    }

    private static Face faceOf(VehicleEntity v, Vec3 hit) {
        Vec3 local = hit.subtract(v.position());
        if (local.y > 1.6) return Face.TOP;
        double yaw = Math.toRadians(v.getYRot());
        // rotate into vehicle space: x = right, z = forward
        double fx = local.x * Math.cos(yaw) - local.z * Math.sin(yaw);
        double fz = local.x * Math.sin(yaw) + local.z * Math.cos(yaw);
        if (Math.abs(fx) > Math.abs(fz)) return fx > 0 ? Face.RIGHT : Face.LEFT;
        return fz > 0 ? Face.FRONT : Face.BACK;
    }

    private static int cellOf(VehicleEntity v, Face face, Vec3 hit, int cells) {
        Vec3 local = hit.subtract(v.position());
        double yaw = Math.toRadians(v.getYRot());
        double fx = local.x * Math.cos(yaw) - local.z * Math.sin(yaw);
        double fz = local.x * Math.sin(yaw) + local.z * Math.cos(yaw);
        double along = switch (face) {
            case FRONT, BACK -> fx;
            case LEFT, RIGHT -> fz;
            case TOP -> fx;
        };
        double half = switch (face) {
            case FRONT, BACK -> Math.max(1.2, v.getBbWidth() / 2.0);
            case LEFT, RIGHT -> Math.max(1.6, v.getBbWidth() / 2.0);
            case TOP -> Math.max(1.6, v.getBbWidth() / 2.0);
        };
        double t = (along + half) / (2 * half);           // 0..1
        int idx = (int) Math.floor(t * cells);
        return Math.max(0, Math.min(cells - 1, idx));
    }

    // ── APS ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!INTERCEPTORS.isEmpty()) tickInterceptors();

        boolean apsOn = SbwDefenseConfig.APS_ENABLE.get();
        int interval = Math.max(1, SbwDefenseConfig.SYNC_INTERVAL.get());

        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            boolean syncNow = level.getGameTime() % interval == 0;
            for (ServerPlayer player : level.players()) {
                if (!(player.getVehicle() instanceof VehicleEntity v)) continue;
                if (v.isWreck() || !isCovered(v)) continue;
                if (syncNow) DefenseNetwork.sync(player, v);
                if (apsOn) tickAps(level, v);
            }
        }
    }

    private static void tickAps(ServerLevel level, VehicleEntity v) {
        ApsState st = aps(v);
        if (st == null || st.max.length == 0) return;
        int sectors = st.max.length;

        // reload - each sector refills to its own capacity (a vehicle may carry 2 up front, 1 aft)
        for (int i = 0; i < sectors; i++) {
            if (st.max[i] <= 0) continue;                    // blind sector, nothing to reload
            if (st.tubes[i] <= 0 && ++st.reload[i] >= SbwDefenseConfig.APS_RELOAD_TICKS.get()) {
                st.tubes[i] = st.max[i];
                st.reload[i] = 0;
            }
        }

        double radius = SbwDefenseConfig.APS_RADIUS.get();
        AABB box = v.getBoundingBox().inflate(radius);
        List<Entity> near = level.getEntities(v, box, e -> e instanceof Projectile && e.isAlive());

        Entity best = null;
        int bestSector = -1;
        double bestDist = Double.MAX_VALUE;
        Vec3 center = v.position().add(0.0, 1.2, 0.0);
        double minClosing = SbwDefenseConfig.APS_MIN_SPEED.get();

        for (Entity e : near) {
            if (INTERCEPTORS.containsKey(e)) continue;      // never engage our own interceptors
            if (isOwnedByVehicleCrew(e, v)) continue;
            if (!isWarhead(e)) continue;                    // arrows / eggs are not worth a tube
            Vec3 rel = e.position().subtract(center);
            double dist = rel.length();
            if (dist < 2.0 || dist > radius) continue;
            // closing velocity: the projectile must be heading at us
            double closing = e.getDeltaMovement().dot(rel.normalize().scale(-1));
            if (closing <= Math.max(0.05, minClosing)) continue;

            int sector = sectorOf(v, rel, sectors);
            if (sector < 0 || sector >= sectors || st.tubes[sector] <= 0) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
                bestSector = sector;
            }
        }
        if (best == null) return;

        // launch from the tube cluster that actually covers the threat, not from the hull centre
        Vec3 muzzle = muzzle(v, bestSector);
        Entity interceptor = launchInterceptor(level, v, best, muzzle);
        if (interceptor == null) return;
        st.tubes[bestSector]--;
        st.reload[bestSector] = 0;
        INTERCEPTORS.put(interceptor, best);
        DefenseNetwork.flashNear(level, muzzle, bestSector);
    }

    /**
     * World position of the launcher cluster covering {@code sectorIndex}: the layout's
     * {@code mount} offset rotated into world space, or the hull centre at y+1.2 when that
     * vehicle entry carries no mount (the old behaviour).
     */
    private static Vec3 muzzle(VehicleEntity v, int sectorIndex) {
        DefenseLayouts.Layout layout = DefenseLayouts.forVehicle(v);
        float[] mount = layout != null && sectorIndex >= 0 && sectorIndex < layout.count()
                ? layout.sectors().get(sectorIndex).mount()
                : null;
        double lx = mount == null ? 0.0 : mount[0];   // +X right
        double ly = mount == null ? 1.2 : mount[1];   // +Y up
        double lz = mount == null ? 0.0 : mount[2];   // +Z forward
        double yaw = Math.toRadians(v.getYRot());
        // MC yaw grows clockwise: forward = (-sin, cos), right = (-cos, -sin)
        double wx = -Math.sin(yaw) * lz - Math.cos(yaw) * lx;
        double wz = Math.cos(yaw) * lz - Math.sin(yaw) * lx;
        return v.position().add(wx, ly, wz);
    }

    /**
     * Which tube group covers {@code rel} (threat position relative to the hull centre).
     *
     * <p>The answer comes from the vehicle's own table - azimuths and tube counts differ per model.
     * Hulls without an entry never get here, because they carry no tubes.
     */
    private static int sectorOf(VehicleEntity v, Vec3 rel, int sectors) {
        // Minecraft yaw: 0 = +Z, increasing clockwise seen from above, so this is the true bearing.
        double bearing = Math.toDegrees(Math.atan2(-rel.x, rel.z));
        double relYaw = ((bearing - v.getYRot()) % 360.0 + 540.0) % 360.0 - 180.0;

        DefenseLayouts.Layout layout = DefenseLayouts.forVehicle(v);
        if (layout != null && layout.count() > 0) return layout.sectorFor(relYaw);

        // no table: split the ring evenly (only reachable if a table lists tubes without sectors)
        double span = 360.0 / Math.max(1, sectors);
        int idx = (int) Math.floor((relYaw + 180.0) / span);
        return Math.max(0, Math.min(sectors - 1, idx));
    }

    private static Entity launchInterceptor(ServerLevel level, VehicleEntity v, Entity threat, Vec3 muzzle) {
        ResourceLocation id = ResourceLocation.tryParse(SbwDefenseConfig.APS_INTERCEPTOR.get());
        if (id == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        Entity e = type.create(level);
        if (e == null) return null;

        Vec3 dir = threat.position().subtract(muzzle);
        if (dir.lengthSqr() < 0.01) return null;
        dir = dir.normalize();
        e.moveTo(muzzle.x + dir.x * 0.6, muzzle.y + dir.y * 0.6, muzzle.z + dir.z * 0.6,
                (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)),
                (float) -Math.toDegrees(Math.asin(dir.y)));
        e.setDeltaMovement(dir.scale(2.2));
        if (e instanceof ThrowableItemProjectile tip && v.getControllingPassenger() instanceof Player p) {
            tip.setOwner(p);
        }
        if (e instanceof MissileProjectile mp) {
            mp.setTargetUuid(threat.getStringUUID());
            mp.setTargetVec(threat.position());
            mp.setLost(false);
        }
        if (!level.addFreshEntity(e)) return null;
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.FIREWORK_ROCKET_LAUNCH,
                SoundSource.PLAYERS, 0.8F, 1.4F);
        return e;
    }

    private static void tickInterceptors() {
        for (Iterator<Map.Entry<Entity, Entity>> it = INTERCEPTORS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Entity> e = it.next();
            Entity mi = e.getKey();
            Entity threat = e.getValue();
            if (mi.isRemoved() || !mi.isAlive() || mi.level() == null) {
                it.remove();
                continue;
            }
            if (threat == null || !threat.isAlive() || threat.isRemoved()) {
                mi.discard();
                it.remove();
                continue;
            }
            // keep the seeker pointed at the threat
            if (mi instanceof MissileProjectile mp) {
                mp.setTargetVec(threat.position());
                mp.setLost(false);
            }
            if (mi.distanceTo(threat) <= SbwDefenseConfig.APS_KILL_RADIUS.get()) {
                detonate(threat);
                if (mi.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.EXPLOSION, mi.getX(), mi.getY(), mi.getZ(),
                            1, 0, 0, 0, 0);
                    level.playSound(null, mi.getX(), mi.getY(), mi.getZ(),
                            SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.9F, 1.3F);
                }
                mi.discard();
                it.remove();
            }
        }
    }

    /**
     * The interceptor takes out the threat's guidance / direct hit, but the warhead itself still
     * goes off where it was met - an intercepted missile is a bang next to the hull, not a silent
     * disappearance. Turn {@code defense.aps.warheadDetonates} off to just delete the projectile.
     */
    private static void detonate(Entity threat) {
        if (SbwDefenseConfig.APS_WARHEAD_DETONATES.get() && threat instanceof FastThrowableProjectile warhead) {
            capExternalBlast(threat, warhead);
            warhead.causeExplode(threat.position());
        }
        if (threat instanceof DestroyableProjectile dp) dp.setHealth(0.0F);
        threat.discard();
    }

    // ── state helpers ──────────────────────────────────────────────────────

    private static EraState era(VehicleEntity v) {
        if (!SbwDefenseConfig.ERA_ENABLE.get()) return null;
        int[] cells = eraCells(v);
        int blocksPerCell = SbwDefenseConfig.ERA_BLOCKS_PER_CELL.get();
        EraState st = ERA.get(v.getUUID());
        // rebuild when the layout changes (vehicle swapped its entry, or the config moved)
        if (st == null || !java.util.Arrays.equals(st.cells, cells) || st.blocksPerCell != blocksPerCell) {
            st = new EraState(cells, blocksPerCell);
            ERA.put(v.getUUID(), st);
        }
        return st;
    }

    /** ERA cell count per face, in Face ordinal order: layout entry first, else the config value. */
    private static int[] eraCells(VehicleEntity v) {
        DefenseLayouts.Layout layout = DefenseLayouts.forVehicle(v);
        if (layout != null && layout.eraCells() != null) return layout.eraCells();
        int n = Math.max(0, SbwDefenseConfig.ERA_CELLS_PER_FACE.get());
        int[] cells = new int[Face.values().length];
        java.util.Arrays.fill(cells, n);
        return cells;
    }

    private static ApsState aps(VehicleEntity v) {
        if (!SbwDefenseConfig.APS_ENABLE.get()) return null;
        int[] max = sectorTubes(v);
        ApsState st = APS.get(v.getUUID());
        // the layout can change between sessions (or when a vehicle id is swapped), so rebuild
        // whenever the sector count no longer matches instead of silently refusing to fire
        if (st == null || st.max.length != max.length) {
            st = new ApsState(max);
            APS.put(v.getUUID(), st);
        }
        return st;
    }

    /**
     * Per-sector tube capacity for this hull. Only vehicles listed in the layout table carry a
     * hard-kill system: no entry (and no {@code default} ring) means the hull has no APS at all.
     */
    private static int[] sectorTubes(VehicleEntity v) {
        DefenseLayouts.Layout layout = DefenseLayouts.forVehicle(v);
        if (layout == null || layout.count() == 0) return new int[0];

        int[] max = new int[layout.count()];
        for (int i = 0; i < max.length; i++) {
            max[i] = Math.max(0, Math.min(8, layout.sectors().get(i).tubes()));
        }
        return max;
    }

    /** How many tube groups this hull carries (0 when uncovered or APS is off) - used by the HUD. */
    public static int apsSectorCount(VehicleEntity v) {
        if (v == null || !isCovered(v) || !SbwDefenseConfig.APS_ENABLE.get()) return 0;
        return sectorTubes(v).length;
    }

    /** Remaining ERA percentage (for HUD), -1 when the vehicle has no ERA / ERA is off. */
    public static int eraPercent(VehicleEntity v) {
        if (v == null || !isCovered(v)) return -1;
        EraState st = era(v);
        return st == null ? -1 : st.percent();
    }

    /** Bitmask of sectors that still have a tube loaded (for the HUD). */
    public static int apsMask(VehicleEntity v) {
        if (v == null || !isCovered(v)) return 0;
        ApsState st = aps(v);
        return st == null ? 0 : st.mask();
    }

    /** Vehicle UUID states are dropped as soon as the hull leaves the level. */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof VehicleEntity v)) return;
        UUID id = v.getUUID();
        ERA.remove(id);
        APS.remove(id);
    }

    // ── shared helpers ─────────────────────────────────────────────────────

    private static boolean isCovered(VehicleEntity v) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).toString();
        List<? extends String> list = SbwDefenseConfig.DEFENSE_VEHICLES.get();
        return list.contains(id) || list.contains("*");
    }

    /** True for missiles / rockets / shells / grenades - the things ERA and APS care about. */
    private static boolean isWarhead(Entity e) {
        if (e == null) return false;
        if (e instanceof DestroyableProjectile) return true;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        if (id == null) return false;
        String path = id.getPath();
        for (String hint : WARHEAD_HINTS) {
            if (path.contains(hint)) return true;
        }
        return false;
    }

    /** Stops a vehicle from shooting down its own shells / its crew's thrown explosives. */
    private static boolean isOwnedByVehicleCrew(Entity projectile, VehicleEntity v) {
        if (!(projectile instanceof Projectile p)) return false;
        Entity owner = p.getOwner();
        if (owner == null) return false;
        if (owner == v) return true;
        return owner.getVehicle() == v;
    }

    private VehicleDefense() {}
}
