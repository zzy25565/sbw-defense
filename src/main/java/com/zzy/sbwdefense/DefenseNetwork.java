package com.zzy.sbwdefense;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Own payload channel - no longer shared with the radar addon. */
public final class DefenseNetwork {

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(DefensePayloads.State.TYPE, DefensePayloads.State.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientDefenseState.set(payload)));

        registrar.playToClient(DefensePayloads.Intercept.TYPE, DefensePayloads.Intercept.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientDefenseState.flash(payload.sector())));
    }

    /** Push the current ERA / APS state of {@code v} to the player crewing it. */
    public static void sync(ServerPlayer player, VehicleEntity v) {
        PacketDistributor.sendToPlayer(player, new DefensePayloads.State(
                v.getId(),
                VehicleDefense.eraPercent(v),
                VehicleDefense.apsMask(v),
                VehicleDefense.apsSectorCount(v),
                SbwDefenseConfig.APS_ENABLE.get()));
    }

    /** Tell nearby players that a tube just fired (HUD flash + soft confirmation of the kill). */
    public static void flashNear(ServerLevel level, Vec3 at, int sector) {
        DefensePayloads.Intercept payload = new DefensePayloads.Intercept(sector);
        double r2 = 96.0 * 96.0;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at) <= r2) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    private DefenseNetwork() {}
}
