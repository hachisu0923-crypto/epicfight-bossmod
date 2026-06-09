package com.example.efbossmvp;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, EfBossMvp.MODID);

    /**
     * The boss entity type. The registry path "dread_knight" MUST match the datapack file name
     * {@code data/efbossmvp/epicfight_mobpatch/dread_knight.json}.
     *
     * <p>NOTE (1.20.1): EntityType.Builder.build(...) takes a String id. On 1.21+ it takes a
     * ResourceKey — do not copy 1.21 snippets here.</p>
     */
    public static final RegistryObject<EntityType<DreadKnightEntity>> DREAD_KNIGHT =
            ENTITIES.register("dread_knight", () ->
                    EntityType.Builder.of(DreadKnightEntity::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)        // humanoid-ish size (biped)
                            .clientTrackingRange(80)
                            .fireImmune()
                            .build("dread_knight"));
}
