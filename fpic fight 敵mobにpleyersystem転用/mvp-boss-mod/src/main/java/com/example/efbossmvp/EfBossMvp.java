package com.example.efbossmvp;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * EpicFight MVP Boss — main mod class.
 *
 * <p>Note: This mod intentionally has NO EpicFight imports. The boss is a plain Forge
 * entity; all EpicFight integration (armature / mesh / renderer / combat) is provided by the
 * datapack {@code data/efbossmvp/epicfight_mobpatch/dread_knight.json}, which EpicFight's
 * MobPatchReloadListener auto-registers on datapack load. EpicFight is declared as a hard
 * dependency in mods.toml.</p>
 */
@Mod(EfBossMvp.MODID)
public class EfBossMvp {

    public static final String MODID = "efbossmvp";

    public EfBossMvp() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        // ModEvents / ClientEvents are wired via @Mod.EventBusSubscriber (auto-registered).
    }
}
