package com.zzy.sbwdefense;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Minimal defensive readout, War Thunder style: one short line, no panels.
 *
 * <pre>
 *  ERA 72%   APS 3/4        (only the parts the vehicle actually has are shown)
 * </pre>
 *
 * The line is colour coded: green while the subsystems are healthy, amber/red when ERA is
 * depleted or every APS tube is empty. A successful interception still flashes "INTERCEPT"
 * next to the line, because that is the one thing the player must not miss.
 */
public final class DefenseHud {

    private static final int OK = 0xFF4FD07A;
    private static final int WARN = 0xFFE8B33C;
    private static final int BAD = 0xFFE0574F;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui) return;
        if (!SbwDefenseClientConfig.HUD_ENABLE.get()) return;
        if (!(player.getVehicle() instanceof VehicleEntity v) || v.isWreck()) return;

        DefensePayloads.State st = ClientDefenseState.get(v.getId());
        if (st == null) return;

        boolean era = st.eraPercent() >= 0;
        boolean aps = st.apsEnabled() && st.sectors() > 0;
        if (!era && !aps) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int x = 8 + SbwDefenseClientConfig.HUD_OFFSET_X.get();
        int y = 8 + SbwDefenseClientConfig.HUD_OFFSET_Y.get();

        boolean labels = SbwDefenseClientConfig.HUD_SHOW_LABELS.get();
        String eraTag = "";
        String apsTag = "";
        if (labels) {
            eraTag = "ERA ";
            apsTag = "APS ";
        }

        // worst subsystem drives the colour
        int color = OK;
        if (era && st.eraPercent() <= 15) {
            color = BAD;
        } else if (era && st.eraPercent() <= 40) {
            color = WARN;
        } else if (aps && apsReady(st) == 0) {
            color = BAD;
        }

        int sectors = Math.min(st.sectors(), 8);
        String line;
        if (era && aps) {
            line = eraTag + st.eraPercent() + "%" + "   " + apsTag + apsReady(st) + "/" + sectors;
        } else if (era) {
            line = eraTag + st.eraPercent() + "%";
        } else {
            line = apsTag + apsReady(st) + "/" + sectors;
        }
        g.drawString(font, line, x, y, color);

        // intercept flash stays, it is the only event that must break through
        float flash = ClientDefenseState.flashLevel();
        if (flash > 0.05F) {
            int alpha = (int) (flash * 255.0F) << 24;
            g.drawString(font, "INTERCEPT", x + font.width(line) + 8, y, alpha | 0x00E8B33C);
        }
    }

    /** Sectors whose tube is loaded right now. */
    private static int apsReady(DefensePayloads.State st) {
        int n = Math.min(st.sectors(), 8);
        int ready = 0;
        for (int i = 0; i < n; i++) {
            if (((st.apsMask() >> i) & 1) != 0) ready++;
        }
        return ready;
    }
}
