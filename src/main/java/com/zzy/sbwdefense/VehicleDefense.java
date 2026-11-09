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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>Every vehicle is updated exactly once per tick even with a full crew, and a threat that
 * already has an interceptor in the air is never assigned a second one: the shot is tracked in
 * {@link #SHOTS}, the target in {@link #ENGAGED}, and the lock is released on kill, on interceptor
 * loss or after {@code defense.aps.engageTimeout} ticks.
 *
 * <p>State is keyed by vehicle UUID (not the transient entity id) so recycled ids can never
 * inherit another tank's spent armour, and it is dropped when the vehicle leaves the level.
 */
public final class VehicleDefense {

    // ── ERA ────────────────────────────────────────────────────────────────

    private enum Face { FRONT, BACK, LEFT, RIGHT, TOP }

    /** Cached {@code Face.values()} - avoids a defensive array copy on every hit. */
    private static final Face[] FACES = Face.values();

    /**
     * How close to a cell seam a hit must land before the neighbouring cell may absorb it.
     * Expressed as a fraction of one cell's width ({@code 0.15} = outer 15 % on either side).
     */
    private static final double SEAM_FRACTION = 0.15;

    private static final class EraState {
        /** Layout this state was built from - compared by identity to spot a table swap. */
        final DefenseLayouts.Layout layout;
        /** remaining blocks, indexed {@code [face ordinal][cell]} */
        final int[][] blocks;
        /** cell count of each face, in Face ordinal order - a layout may leave a face bare */
        final int[] cells;
        final int blocksPerCell;
        final int total;
        int left;

        EraState(DefenseLayouts.Layout layout, int[] cellsPerFace, int blocksPerCell) {
            this.layout = layout;
            this.cells = cellsPerFace.clone();
            this.blocksPerCell = blocksPerCell;
            this.blocks = new int[FACES.length][];
            int sum = 0;
            for (int f = 0; f < FACES.length; f++) {
                int n = Math.max(0, cells[f]);
                blocks[f] = new int[n];
                if (n > 0) Arrays.fill(blocks[f], blocksPerCell);
                sum += n * blocksPerCell;
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
        /** Layout this state was built from - compared by identity to spot a table swap. */
        final DefenseLayouts.Layout layout;
        /** capacity per sector, straight from the vehicle's layout (0 = blind spot) */
        final int[] max;
        final int[] tubes;
        final int[] reload;

        ApsState(DefenseLayouts.Layout layout, int[] maxTubes) {
            this.layout = layout;
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

    /** One interceptor in flight: what it was fired at, from which hull and tube group. */
    private static final class Shot {
        final Entity threat;
        final VehicleEntity vehicle;
        final int sector;
        int ttl;

        Shot(Entity threat, VehicleEntity vehicle, int sector, int ttl) {
            this.threat = threat;
            this.vehicle = vehicle;
            this.sector = sector;
            this.ttl = ttl;
        }
    }

    private static final Map<UUID, EraState> ERA = new HashMap<>();
    private static final Map<UUID, ApsState> APS = new HashMap<>();
    /** interceptor entity -> the shot it belongs to */
    private static final Map<Entity, Shot> SHOTS = new HashMap<>();
    /** threat entity -> the interceptor already assigned to it (no double spending) */
    private static final Map<Entity, Entity> ENGAGED = new HashMap<>();
    /** vehicles already ticked this server tick - one update per hull, no matter the crew */
    private static final Set<UUID> TICKED = new HashSet<>();

    /** Registry-path keywords treated as chemical-energy / warhead threats. */
    private static final String[] WARHEAD_HINTS = {
            "shell", "missile", "rocket", "grenade", "bomb", "rpg", "torpedo",
            "cannon", "apfsds", "heat", "hesh", "atgm"
    };

    /** Warhead verdict per entity type - the registry path never changes at runtime. */
    private static final Map<EntityType<?>, Boolean> WARHEAD_CACHE = new ConcurrentHashMap<>();
    /** {@code defense.vehicles} verdict per entity type - dropped when the config list changes. */
    private static final Map<EntityType<?>, Boolean> COVERED_CACHE = new ConcurrentHashMap<>();
    private static List<? extends String> coveredListRef;

    // ── ERA hit handling ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onProjectileHit(ProjectileHitEvent.HitEntity event) {
        if (!SbwDefenseConfig.ERA_ENABLE.get()) return;
        if (!(event.getTarget() instanceof VehicleEntity vehicle) || vehicle.isWreck()) return;
        if (!isCovered(vehicle)) return;
        if (SbwDefenseConfig.ERA_ONLY_EXPLOSIVE.get() && !isWarhead(event.getProjectile())) return;

        EraState st = era(vehicle);
        if (st == null || st.left <= 0) return;

        // one world -> hull conversion feeds both the face and the cell, so they can never disagree
        Vec3 local = toLocal(vehicle, event.getHitVec());
        Face face = faceOf(local);
        int fi = face.ordinal();
        int cells = st.cells[fi];
        if (cells <= 0) return;                  // this face carries no ERA at all
        double t = alongT(vehicle, face, local);
        int use = pickCell(st.blocks[fi], t);
        if (use < 0) return;                     // this face is stripped: normal penetration

        st.blocks[fi][use]--;
        st.left--;

        // A block is spent either way. What differs is how much of the round survives:
        //  * external ammo -> the hit itself is fully stopped (damage = 0) but the warhead still
        //                     cooks off outside the plate; its blast is capped so a point-blank
        //                     cook-off cannot wreck the hull.
        //  * internal ammo -> scale the projectile's own damage / explosion fields and let SBW's
        //                     normal pipeline run, so armour, impact angle and part damage apply.
        boolean external = isExternalAmmo(event.getProjectile());
        float keep = 1.0F - reductionFor(external);
        if (event.getProjectile() instanceof IBulletProperties warhead) {
            warhead.setDamage(warhead.getDamage() * keep);
            if (external) {
                if (SbwDefenseConfig.ERA_KEEP_EXPLOSION.get()) {
                    capExternalBlast(warhead, external);
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

    /**
     * World offset -&gt; hull space, shared by hit location and muzzle placement.
     *
     * <p>Minecraft yaw grows clockwise seen from above, so a hull facing {@code yaw} has
     * {@code forward = (-sin, 0, cos)} and {@code right = forward × up = (-cos, 0, -sin)}.
     * Rotating the offset onto those two basis vectors is the inverse of the muzzle transform;
     * the two must always use the same convention or front hits land on the rear plate at 90°.
     *
     * @return {@code (x = right, y = up, z = forward)} relative to the hull origin
     */
    private static Vec3 toLocal(VehicleEntity v, Vec3 world) {
        Vec3 d = world.subtract(v.position());
        double yaw = Math.toRadians(v.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new Vec3(
                -d.x * cos - d.z * sin,   // right
                d.y,                      // up
                -d.x * sin + d.z * cos);  // forward
    }

    /** Face of a hit, given hull-space offset. */
    private static Face faceOf(Vec3 local) {
        if (local.y > 1.6) return Face.TOP;
        if (Math.abs(local.x) > Math.abs(local.z)) return local.x > 0 ? Face.RIGHT : Face.LEFT;
        return local.z > 0 ? Face.FRONT : Face.BACK;
    }

    /** Position of the hit along its face, normalised to 0..1 (may fall slightly outside). */
    private static double alongT(VehicleEntity v, Face face, Vec3 local) {
        double along = switch (face) {
            case FRONT, BACK -> local.x;   // left-to-right sweep across the glacis / tail plate
            case LEFT, RIGHT -> local.z;   // front-to-back sweep along the skirt
            case TOP -> local.x;
        };
        double half = switch (face) {
            case FRONT, BACK -> Math.max(1.2, v.getBbWidth() / 2.0);
            case LEFT, RIGHT, TOP -> Math.max(1.6, v.getBbWidth() / 2.0);
        };
        return (along + half) / (2 * half);
    }

    /** Cell index for a normalised face coordinate, clamped into the face. */
    private static double clampT(double t) {
        if (t <= 0.0) return 0.0;
        return t >= 1.0 ? Math.nextDown(1.0) : t;
    }

    /**
     * Cell that actually absorbs the hit: the cell under {@code t} when it still holds a block,
     * otherwise a neighbour - but only when the hit landed within {@link #SEAM_FRACTION} of the
     * shared seam. A hit in the middle of a stripped cell passes through instead of magically
     * eating the block two metres away, and the first and last cell of a face are never treated
     * as adjacent.
     *
     * <p>The cell index is derived here from {@code t} rather than passed in, so the two can
     * never disagree (a mismatch would make {@code within} fall outside 0..1 and borrow a
     * neighbour that is nowhere near the hit).
     *
     * @return cell index to spend, or {@code -1} when nothing covers this point
     */
    private static int pickCell(int[] cellBlocks, double t) {
        int n = cellBlocks.length;
        if (n == 0) return -1;

        double clamped = clampT(t);
        int cell = Math.min(n - 1, (int) Math.floor(clamped * n));
        if (cellBlocks[cell] > 0) return cell;

        double within = clamped * n - cell;      // 0..1, where the hit sits inside this cell
        if (within < SEAM_FRACTION && cell > 0 && cellBlocks[cell - 1] > 0) return cell - 1;
        if (1.0 - within < SEAM_FRACTION && cell < n - 1 && cellBlocks[cell + 1] > 0) return cell + 1;
        return -1;
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
    private static void capExternalBlast(IBulletProperties warhead, boolean external) {
        if (!external) return;
        float cap = (float) (double) SbwDefenseConfig.EXTERNAL_BLAST_CAP.get();
        if (warhead.getExplosionDamage() > cap) warhead.setExplosionDamage(cap);
    }

    /**
     * ERA reduction for this round: Superb Warfare's own ammo ({@code superbwarfare:*}) uses the
     * internal figure, every other namespace (addons, other mods) the external one.
     */
    private static float reductionFor(boolean external) {
        double v = external
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

    // ── APS ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SHOTS.isEmpty()) tickInterceptors();

        boolean apsOn = SbwDefenseConfig.APS_ENABLE.get();
        int interval = Math.max(1, SbwDefenseConfig.SYNC_INTERVAL.get());

        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            boolean syncNow = level.getGameTime() % interval == 0;
            TICKED.clear();   // hulls never span levels, so one set per level is enough
            for (ServerPlayer player : level.players()) {
                if (!(player.getVehicle() instanceof VehicleEntity v)) continue;
                if (v.isWreck() || !isCovered(v)) continue;
                if (syncNow) DefenseNetwork.sync(player, v);
                // a four-man crew must not run four APS updates (and four reload ticks) per tick
                if (apsOn && TICKED.add(v.getUUID())) tickAps(level, v);
            }
        }
    }

    private static void tickAps(ServerLevel level, VehicleEntity v) {
        ApsState st = aps(v);
        if (st == null || st.max.length == 0) return;
        final int sectors = st.max.length;

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
            if (SHOTS.containsKey(e)) continue;              // never engage our own interceptors
            if (ENGAGED.containsKey(e)) continue;            // already has a round in the air
            if (isOwnedByVehicleCrew(e, v)) continue;
            if (!isWarhead(e)) continue;                     // arrows / eggs are not worth a tube
            Vec3 rel = e.position().subtract(center);
            double dist = rel.length();
            if (dist < 2.0 || dist > radius) continue;
            // closing velocity: the projectile must be heading at us
            double closing = e.getDeltaMovement().dot(rel.normalize().scale(-1));
            if (closing <= Math.max(0.05, minClosing)) continue;

            int sector = sectorOf(v, st.layout, rel, sectors);
            if (sector < 0 || sector >= sectors || st.tubes[sector] <= 0) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
                bestSector = sector;
            }
        }
        if (best == null) return;

        // launch from the tube cluster that actually covers the threat, not from the hull centre
        Vec3 muzzle = muzzle(v, st.layout, bestSector);
        Entity interceptor = launchInterceptor(level, v, best, muzzle);
        if (interceptor == null) return;
        st.tubes[bestSector]--;
        st.reload[bestSector] = 0;

        int ttl = Math.max(1, SbwDefenseConfig.APS_ENGAGE_TIMEOUT.get());
        SHOTS.put(interceptor, new Shot(best, v, bestSector, ttl));
        ENGAGED.put(best, interceptor);   // this threat is taken: no second tube until released
        DefenseNetwork.notifyLaunch(v, bestSector);
    }

    /**
     * World position of the launcher cluster covering {@code sectorIndex}: the layout's
     * {@code mount} offset rotated into world space, or the hull centre at y+1.2 when that
     * vehicle entry carries no mount (the old behaviour).
     */
    private static Vec3 muzzle(VehicleEntity v, DefenseLayouts.Layout layout, int sectorIndex) {
        float[] mount = layout != null && sectorIndex >= 0 && sectorIndex < layout.count()
                ? layout.sectors().get(sectorIndex).mount()
                : null;
        double lx = mount == null ? 0.0 : mount[0];   // +X right
        double ly = mount == null ? 1.2 : mount[1];   // +Y up
        double lz = mount == null ? 0.0 : mount[2];   // +Z forward
        double yaw = Math.toRadians(v.getYRot());
        // inverse of toLocal(): forward = (-sin, cos), right = (-cos, -sin)
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
    private static int sectorOf(VehicleEntity v, DefenseLayouts.Layout layout, Vec3 rel, int sectors) {
        // Minecraft yaw: 0 = +Z, increasing clockwise seen from above, so this is the true bearing.
        double bearing = Math.toDegrees(Math.atan2(-rel.x, rel.z));
        double relYaw = ((bearing - v.getYRot()) % 360.0 + 540.0) % 360.0 - 180.0;

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
        for (Iterator<Map.Entry<Entity, Shot>> it = SHOTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Shot> entry = it.next();
            Entity mi = entry.getKey();
            Shot shot = entry.getValue();
            Entity threat = shot.threat;

            if (mi.isRemoved() || !mi.isAlive() || mi.level() == null) {
                it.remove();
                ENGAGED.remove(threat, mi);      // interceptor lost: let the next tube try
                continue;
            }
            if (threat == null || !threat.isAlive() || threat.isRemoved()) {
                mi.discard();
                it.remove();
                ENGAGED.remove(threat, mi);
                continue;
            }
            if (--shot.ttl <= 0) {
                // never caught up (evasive or faster than the interceptor): release the lock
                mi.discard();
                it.remove();
                ENGAGED.remove(threat, mi);
                continue;
            }
            // keep the seeker pointed at the threat
            if (mi instanceof MissileProjectile mp) {
                mp.setTargetVec(threat.position());
                mp.setLost(false);
            }
            if (mi.distanceTo(threat) <= SbwDefenseConfig.APS_KILL_RADIUS.get()) {
                detonate(threat);
                if (shot.vehicle != null && shot.vehicle.isAlive() && !shot.vehicle.isRemoved()) {
                    DefenseNetwork.notifyKill(shot.vehicle, shot.sector);
                }
                if (mi.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.EXPLOSION, mi.getX(), mi.getY(), mi.getZ(),
                            1, 0, 0, 0, 0);
                    level.playSound(null, mi.getX(), mi.getY(), mi.getZ(),
                            SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.9F, 1.3F);
                }
                mi.discard();
                it.remove();
                ENGAGED.remove(threat, mi);
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
            capExternalBlast(warhead, isExternalAmmo(threat));
            warhead.causeExplode(threat.position());
        }
        if (threat instanceof DestroyableProjectile dp) dp.setHealth(0.0F);
        threat.discard();
    }

    // ── state helpers ──────────────────────────────────────────────────────

    private static EraState era(VehicleEntity v) {
        if (!SbwDefenseConfig.ERA_ENABLE.get()) return null;
        DefenseLayouts.Layout layout = DefenseLayouts.forVehicle(v);
        int[] cells = eraCells(layout);
        int blocksPerCell = SbwDefenseConfig.ERA_BLOCKS_PER_CELL.get();
        EraState st = ERA.get(v.getUUID());
        // rebuild when the layout changes (vehicle swapped its entry, or the config moved)
        if (st != null && st.layout == layout
                && st.blocksPerCell == blocksPerCell && Arrays.equals(st.cells, cells)) {
            return st;
        }
        st = new EraState(layout, cells, blocksPerCell);
        ERA.put(v.getUUID(), st);
        return st;
    }

    /** ERA cell count per face, in Face ordinal order: layout entry first, else the config value. */
    private static int[] eraCells(DefenseLayouts.Layout layout) {
        if (layout != null && layout.eraCells() != null) return layout.eraCells();
        int n = Math.max(0, SbwDefenseConfig.ERA_CELLS_PER_FACE.get());
        int[] cells = new int[FACES.length];
        Arrays.fill(cells, n);
        return cells;
    }

    private static ApsState aps(VehicleEntity v) {
        if (!SbwDefenseConfig.APS_ENABLE.get()) return null;
        DefenseLayouts.Layout layout = DefenseLayouts.forVehicle(v);
        ApsState st = APS.get(v.getUUID());
        int sectors = layout == null ? 0 : layout.count();
        if (st != null && st.layout == layout && st.max.length == sectors) return st;

        int[] max = new int[sectors];
        for (int i = 0; i < sectors; i++) {
            max[i] = Math.max(0, Math.min(8, layout.sectors().get(i).tubes()));
        }
        st = new ApsState(layout, max);
        APS.put(v.getUUID(), st);
        return st;
    }

    /** How many tube groups this hull carries (0 when uncovered or APS is off) - used by the HUD. */
    public static int apsSectorCount(VehicleEntity v) {
        if (v == null || !isCovered(v)) return 0;
        ApsState st = aps(v);
        return st == null ? 0 : st.max.length;
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
        List<? extends String> list = SbwDefenseConfig.DEFENSE_VEHICLES.get();
        // NeoForge hands out a new list after a config reload - drop the verdicts with it
        if (list != coveredListRef) {
            coveredListRef = list;
            COVERED_CACHE.clear();
        }
        Boolean cached = COVERED_CACHE.get(v.getType());
        if (cached != null) return cached;

        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(v.getType());
        boolean covered = false;
        if (id != null) {
            String key = id.toString();
            // a hand-written entry may still say sbw_vls:<name> (VLS < 1.0.0b namespace)
            covered = list.contains("*")
                    || list.contains(key)
                    || list.contains(DefenseLayouts.alias(key));
        }
        COVERED_CACHE.put(v.getType(), covered);
        return covered;
    }

    /** True for missiles / rockets / shells / grenades - the things ERA and APS care about. */
    private static boolean isWarhead(Entity e) {
        if (e == null) return false;
        if (e instanceof DestroyableProjectile) return true;

        EntityType<?> type = e.getType();
        Boolean cached = WARHEAD_CACHE.get(type);
        if (cached != null) return cached;

        boolean warhead = false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id != null) {
            String path = id.getPath();
            for (String hint : WARHEAD_HINTS) {
                if (path.contains(hint)) {
                    warhead = true;
                    break;
                }
            }
        }
        WARHEAD_CACHE.put(type, warhead);
        return warhead;
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
