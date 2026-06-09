package com.example.efbossmvp;

import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * MOD event bus subscribers (common side): attributes, spawn placement, creative tab.
 */
@Mod.EventBusSubscriber(modid = EfBossMvp.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEvents {

    @SubscribeEvent
    public static void onAttributeCreate(EntityAttributeCreationEvent event) {
        // Required: a LivingEntity without registered attributes crashes on spawn.
        event.put(ModEntities.DREAD_KNIGHT.get(), DreadKnightEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // SpawnPlacements.register must run on the main thread.
        event.enqueueWork(() ->
                SpawnPlacements.register(ModEntities.DREAD_KNIGHT.get(),
                        SpawnPlacements.Type.ON_GROUND,                 // 1.20.1: SpawnPlacements.Type
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        Monster::checkMonsterSpawnRules));
    }

    @SubscribeEvent
    public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.DREAD_KNIGHT_SPAWN_EGG.get());
        }
    }
}
