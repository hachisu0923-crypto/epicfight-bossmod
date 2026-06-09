package com.example.efbossmvp;

import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, EfBossMvp.MODID);

    /**
     * Spawn egg for the boss. Use ForgeSpawnEggItem (Supplier-based); the vanilla SpawnEggItem
     * constructor is deprecated for modded entities.
     */
    public static final RegistryObject<Item> DREAD_KNIGHT_SPAWN_EGG =
            ITEMS.register("dread_knight_spawn_egg", () ->
                    new ForgeSpawnEggItem(ModEntities.DREAD_KNIGHT, 0x223344, 0xCC2222, new Item.Properties()));
}
