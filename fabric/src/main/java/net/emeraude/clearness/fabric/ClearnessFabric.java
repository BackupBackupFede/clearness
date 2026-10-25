package net.emeraude.clearness.fabric;

import net.emeraude.clearness.Clearness;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric entry point (both sides). All logic is loader-agnostic in {@link Clearness}. */
public final class ClearnessFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Clearness.init(FabricLoader.getInstance().getConfigDir());
    }
}
