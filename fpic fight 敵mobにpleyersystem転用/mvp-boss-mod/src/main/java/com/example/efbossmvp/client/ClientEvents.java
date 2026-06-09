package com.example.efbossmvp.client;

import com.example.efbossmvp.DreadKnightEntity;
import com.example.efbossmvp.EfBossMvp;
import com.example.efbossmvp.ModEntities;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * MOD event bus subscribers (client side): register a vanilla-style humanoid renderer.
 *
 * <p>This renderer is REQUIRED even though EpicFight will wrap/override it: without a base
 * renderer the entity has nothing to render. EpicFight patches this renderer at datapack load
 * (the datapack's {@code "renderer": "minecraft:zombie"} field reuses the built-in biped
 * patched renderer). We reuse the vanilla zombie model+texture so no custom assets are needed.</p>
 */
@Mod.EventBusSubscriber(modid = EfBossMvp.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    private static final ResourceLocation ZOMBIE_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.DREAD_KNIGHT.get(),
                ctx -> new HumanoidMobRenderer<DreadKnightEntity, HumanoidModel<DreadKnightEntity>>(
                        ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.ZOMBIE)), 0.5F) {
                    @Override
                    public ResourceLocation getTextureLocation(DreadKnightEntity entity) {
                        return ZOMBIE_TEXTURE;
                    }
                });
    }
}
