package net.emeraude.clearness.neoforge;

import net.emeraude.clearness.Clearness;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

/** NeoForge entry point (both sides). All logic is loader-agnostic in {@link Clearness}. */
@Mod(Clearness.MOD_ID)
public final class ClearnessNeoForge {

    public ClearnessNeoForge(IEventBus modBus, ModContainer container) {
        Clearness.init(FMLPaths.CONFIGDIR.get());
    }
}
