package com.example.efbossmvp.efcompat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.efbossmvp.EfBossMvp;
import com.example.efbossmvp.RoninEntity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Layer 3 — reactive "player-skill" behaviour for the Ronin boss, expressed through Forge events
 * that read the live Epic Fight patch. This covers the three skills Indestructible's datapack layer
 * cannot: <b>Step</b> (i-frame dodge), <b>Phantom Ascent</b> (leap) and <b>Emergency Escape</b>
 * (cancel-into-dodge when caught).
 *
 * <p><b>BUILD / RUNTIME NOTE.</b> This class compiles against the Epic Fight API
 * ({@code yesman.epicfight.*}), so the mod needs Epic Fight as a {@code implementation} dependency and
 * must be built in a 1.20.1 + Epic Fight dev environment. Epic Fight's API is version-sensitive — if a
 * signature drifts, adjust here and re-test. The numbers below are first-pass and meant to be tuned
 * in-game (weight/cooldown/chance) so the boss reads as skilled, not unfair. <b>Removing this single
 * package leaves the Layer 1/2 datapack boss (attacks + guard/parry/stamina) fully functional.</b></p>
 *
 * <p>Verified API surface (Epic Fight 1.20.1 source):
 * {@code EpicFightCapabilities.getEntityPatch(Entity, Class)},
 * {@code LivingEntityPatch#playAnimationSynchronized(AssetAccessor, float)},
 * {@code LivingEntityPatch#getEntityState()} → {@code EntityState#getLevel()} (0 free / 1 anticipation
 * / 2 contact / 3 recovery), {@code LivingEntityPatch#getTarget()},
 * {@code AnimationManager.byKey(String)}.</p>
 */
@Mod.EventBusSubscriber(modid = EfBossMvp.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RoninSkillAI {

    private RoninSkillAI() {}

    // --- Step dodge animation keys (verified Epic Fight Step skill assets) ---
    private static final String STEP_LEFT     = "epicfight:biped/skill/step_left";
    private static final String STEP_RIGHT    = "epicfight:biped/skill/step_right";
    private static final String STEP_BACKWARD = "epicfight:biped/skill/step_backward";
    // Phantom-Ascent visual: the uchigatana leaping air slash doubles as a gap-closing pounce.
    private static final String AIR_SLASH     = "epicfight:biped/combat/uchigatana_airslash";

    // --- Tunables (game ticks / blocks) ---
    private static final int    DODGE_COOLDOWN   = 35;   // min ticks between reactive dodges
    private static final int    DODGE_IFRAMES    = 8;    // guaranteed invulnerability window after a dodge
    private static final double DODGE_RANGE      = 4.0;  // only react to threats this close
    private static final int    LEAP_COOLDOWN    = 200;  // ticks between Phantom-Ascent leaps
    private static final double LEAP_MIN_RANGE   = 6.0;
    private static final double LEAP_MAX_RANGE   = 16.0;
    private static final double LEAP_UP          = 0.62; // vertical impulse
    private static final double LEAP_FORWARD     = 0.85; // horizontal impulse toward target
    private static final float  EMERGENCY_HEALTH = 0.40f; // escape more eagerly below this HP fraction
    private static final double EMERGENCY_CHANCE = 0.6;   // not every caught hit triggers an escape

    // Server-side per-entity timers, keyed by entity UUID (value = absolute game time in ticks).
    private static final Map<UUID, Long> DODGE_READY_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LEAP_READY_AT  = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> IFRAME_UNTIL   = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onLivingTick(LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof RoninEntity) || entity.level().isClientSide) {
            return;
        }
        LivingEntityPatch<?> self = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        if (self == null) {
            return;
        }
        LivingEntity target = ((Mob) entity).getTarget(); // RoninEntity is a Monster (Mob)
        if (target == null || !target.isAlive()) {
            return;
        }
        long now = entity.level().getGameTime();
        double distSqr = entity.distanceToSqr(target);

        // --- Step: dodge when the target is winding up an attack within reach ---
        if (now >= DODGE_READY_AT.getOrDefault(entity.getUUID(), 0L) && distSqr <= DODGE_RANGE * DODGE_RANGE) {
            LivingEntityPatch<?> tp = EpicFightCapabilities.getEntityPatch(target, LivingEntityPatch.class);
            if (tp != null && tp.getEntityState().getLevel() == 1) { // target in attack anticipation
                stepDodge(self, entity, now);
            }
        }

        // --- Phantom Ascent: leap-pounce to close a mid-range gap ---
        if (entity.onGround()
                && now >= LEAP_READY_AT.getOrDefault(entity.getUUID(), 0L)
                && distSqr >= LEAP_MIN_RANGE * LEAP_MIN_RANGE
                && distSqr <= LEAP_MAX_RANGE * LEAP_MAX_RANGE) {
            phantomAscent(self, entity, target, now);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof RoninEntity) || entity.level().isClientSide) {
            return;
        }
        long now = entity.level().getGameTime();

        // Hard i-frames: damage taken inside a fresh dodge window is fully negated.
        if (now < IFRAME_UNTIL.getOrDefault(entity.getUUID(), 0L)) {
            event.setCanceled(true);
            return;
        }

        // Emergency Escape: if struck while in attack-recovery or while low on HP, there's a chance to
        // cancel the hit and immediately dodge clear (respects the shared dodge cooldown).
        if (now < DODGE_READY_AT.getOrDefault(entity.getUUID(), 0L)) {
            return;
        }
        LivingEntityPatch<?> self = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        if (self == null) {
            return;
        }
        boolean inRecovery = self.getEntityState().getLevel() == 3;
        boolean lowHealth  = entity.getHealth() / entity.getMaxHealth() < EMERGENCY_HEALTH;
        if ((inRecovery || lowHealth) && entity.getRandom().nextDouble() < EMERGENCY_CHANCE) {
            event.setCanceled(true);
            stepDodge(self, entity, now);
        }
    }

    /** Play a directional Step animation, grant i-frames and start the dodge cooldown. */
    private static void stepDodge(LivingEntityPatch<?> self, LivingEntity entity, long now) {
        int pick = entity.getRandom().nextInt(3);
        String key = (pick == 0) ? STEP_LEFT : (pick == 1) ? STEP_RIGHT : STEP_BACKWARD;
        var anim = AnimationManager.byKey(key);
        self.playAnimationSynchronized(anim, 0.0F);
        IFRAME_UNTIL.put(entity.getUUID(), now + DODGE_IFRAMES);
        DODGE_READY_AT.put(entity.getUUID(), now + DODGE_COOLDOWN);
    }

    /** Launch toward the target with an upward+forward impulse and the leaping air-slash animation. */
    private static void phantomAscent(LivingEntityPatch<?> self, LivingEntity entity,
                                      LivingEntity target, long now) {
        Vec3 toTarget = target.position().subtract(entity.position());
        Vec3 flat = new Vec3(toTarget.x, 0.0D, toTarget.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 dir = flat.normalize();
        entity.setDeltaMovement(dir.x * LEAP_FORWARD, LEAP_UP, dir.z * LEAP_FORWARD);
        entity.hasImpulse = true;
        var anim = AnimationManager.byKey(AIR_SLASH);
        self.playAnimationSynchronized(anim, 0.0F);
        LEAP_READY_AT.put(entity.getUUID(), now + LEAP_COOLDOWN);
    }
}
