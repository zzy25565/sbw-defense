package com.zzy.sbwdefense;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -&gt; client payloads for the ERA / APS panel. */
public final class DefensePayloads {

    /**
     * Full defensive state of the vehicle the player is riding.
     *
     * @param eraPercent remaining ERA percentage, -1 when the hull has no reactive armour
     * @param apsMask    bitmask of sectors that still hold a loaded tube
     * @param sectors    configured sector count (0 when APS is off)
     */
    public record State(int vehicleId, int eraPercent, int apsMask, int sectors, boolean apsEnabled)
            implements CustomPacketPayload {

        public static final Type<State> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(SbwDefenseMod.MODID, "state"));

        public static final StreamCodec<FriendlyByteBuf, State> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.vehicleId());
                    buf.writeByte(p.eraPercent() + 1);      // -1 -> 0 on the wire
                    buf.writeVarInt(p.apsMask());
                    buf.writeByte(p.sectors());
                    buf.writeBoolean(p.apsEnabled());
                },
                buf -> new State(buf.readVarInt(), buf.readUnsignedByte() - 1,
                        buf.readVarInt(), buf.readUnsignedByte(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Fired the moment an interceptor leaves a tube; drives the HUD flash. */
    public record Intercept(int sector) implements CustomPacketPayload {

        public static final Type<Intercept> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(SbwDefenseMod.MODID, "intercept"));

        public static final StreamCodec<FriendlyByteBuf, Intercept> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeByte(p.sector()),
                buf -> new Intercept(buf.readUnsignedByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private DefensePayloads() {}
}
