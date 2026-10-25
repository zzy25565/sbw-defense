package com.zzy.sbwdefense;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主防与爆反 / SBW Defense - standalone ERA + APS addon for Superb Warfare.
 *
 * <p>Split out of the radar addon (sbw_radar 1.5.x) so the armour side can be updated,
 * configured and shipped on its own. Only depends on Superb Warfare itself:
 *
 * <ul>
 *   <li><b>ERA</b> - per-block heavy reactive armour: the hull is split into faces and
 *       cells, a hit consumes the block covering the hit point (event cancelled).</li>
 *   <li><b>APS</b> - hard-kill active protection: closing projectiles inside the detection
 *       ring are engaged by a real interceptor entity launched from the covering sector.</li>
 *   <li><b>HUD</b> - own bottom-left panel (ERA percentage + per-sector tube status), no
 *       longer piggy-backing on the radar state packet.</li>
 * </ul>
 */
@Mod(SbwDefenseMod.MODID)
public final class SbwDefenseMod {
    public static final String MODID = "sbw_defense";
    public static final Logger LOGGER = LoggerFactory.getLogger("SBW Defense");

    public SbwDefenseMod(IEventBus bus) {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, SbwDefenseConfig.SPEC);
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SbwDefenseClientConfig.SPEC);

        bus.addListener(DefenseNetwork::register);

        NeoForge.EVENT_BUS.register(VehicleDefense.class);

        // per-vehicle APS tube positions / ERA coverage live in config/sbw_defense-vehicle-layouts.json
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> DefenseLayouts.bootstrap(LOGGER));

        // The HUD touches client-only classes; only wire it on a physical client.
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.register(DefenseHud.class);
        }
    }
}
