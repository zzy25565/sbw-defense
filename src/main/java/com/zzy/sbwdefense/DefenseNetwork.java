package com.zzy.sbwdefense;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
                (payload, context) -> context.enqueueWork(() -> ClientDefenseState.flash(
                        payload.vehicleId(), payload.sector(), payload.killed())));
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

    /** A tube just fired. Only the crew of that hull hears about it, and only as a soft hint. */
    public static void notifyLaunch(VehicleEntity v, int sector) {
        sendToCrew(v, new DefensePayloads.Intercept(v.getId(), sector, false));
    }

    /** The interceptor reached its target - this is the one that gets the INTERCEPT banner. */
    public static void notifyKill(VehicleEntity v, int sector) {
        sendToCrew(v, new DefensePayloads.Intercept(v.getId(), sector, true));
    }

    /**
     * Crew only: broadcasting to everyone within 96 blocks made other people's tanks flash a
     * "your APS killed something" banner on your HUD. The HUD filters by vehicle id as well.
     */
    private static void sendToCrew(VehicleEntity v, DefensePayloads.Intercept payload) {
        for (Entity passenger : v.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private DefenseNetwork() {}
}
