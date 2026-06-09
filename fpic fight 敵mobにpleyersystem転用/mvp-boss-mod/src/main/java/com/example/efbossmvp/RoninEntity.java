package com.example.efbossmvp;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * "Ronin" — an uchigatana duelist boss (humanoid).
 *
 * <p>Layering:</p>
 * <ul>
 *   <li><b>Layer 1/2 (datapack)</b>: {@code data/efbossmvp/advanced_mobpatch/ronin.json}
 *       (Epic Fight - Indestructible) drives animated attacks, real Guard / Parry / counter and
 *       the stamina + stun-shield mechanics. The registry path "ronin" MUST match that file name.</li>
 *   <li><b>Layer 3 (Java)</b>: {@link com.example.efbossmvp.efcompat.RoninSkillAI} adds the reactive
 *       Step dodge, Phantom-Ascent leap and Emergency Escape by reading the live EpicFight patch.</li>
 * </ul>
 *
 * <p>The vanilla goals below are only a movement/approach baseline; EpicFight's mob patch manages
 * the animated combat once a UCHIGATANA-category weapon is held.</p>
 */
public class RoninEntity extends Monster {

    /** Epic Fight's own uchigatana item: holding it puts the mob in the UCHIGATANA / two_hand bucket. */
    private static final ResourceLocation EPICFIGHT_UCHIGATANA =
            new ResourceLocation("epicfight", "uchigatana");

    public RoninEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Baseline melee/approach. EpicFight (via the datapack patch) drives the animated attacks;
        // RoninSkillAI drives the reactive dodges. This remains a harmless vanilla fallback.
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0D, false));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    /**
     * Equip the uchigatana on spawn. The datapack {@code combat_behavior} / {@code custom_guard_motion}
     * blocks are keyed to the {@code ["uchigatana"]} category + {@code two_hand} style, so the katana
     * combos and the Indestructible guard/parry only activate while an UCHIGATANA item is held. We use
     * EpicFight's own {@code epicfight:uchigatana} (looked up by id so this class keeps no compile-time
     * EpicFight import) and zero its drop chance for a stable loadout on every spawn path. If EpicFight
     * is somehow absent or renamed the item, we fall back to a netherite sword so the mob still spawns.
     */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        Item uchigatana = ForgeRegistries.ITEMS.getValue(EPICFIGHT_UCHIGATANA);
        ItemStack weapon = (uchigatana != null && uchigatana != Items.AIR)
                ? new ItemStack(uchigatana)
                : new ItemStack(Items.NETHERITE_SWORD);
        this.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        return data;
    }

    /** Registered via EntityAttributeCreationEvent (see ModEvents). Agile duelist profile. */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 180.0D)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)        // nimble, faster than the Dread Knight
                .add(Attributes.FOLLOW_RANGE, 40.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.4D);
    }
}
