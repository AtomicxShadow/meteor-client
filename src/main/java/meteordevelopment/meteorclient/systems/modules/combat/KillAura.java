/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class KillAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgElytra = settings.createGroup("Elytra Target");

    // General
    private final Setting<AttackItems> attackWhenHolding = sgGeneral.add(new EnumSetting.Builder<AttackItems>()
        .name("attack-when-holding")
        .description("Only attacks an entity when a specified item is in your hand.")
        .defaultValue(AttackItems.Any)
        .build());

    // Targeting (orijinal kısım aynı kalıyor)
    // ... (orijinal targeting ayarlarını buraya dokunmadan bırak)

    // Timing (orijinal kısım aynı kalıyor)
    // ... (orijinal timing ayarlarını buraya dokunmadan bırak)

    // ────────────────────────────────────────────────────────────────
    //                          ELYTRA TARGET AYARLARI
    // ────────────────────────────────────────────────────────────────

    // Elytra Modu Seçimi
    private final Setting<ElytraFollowMode> elytraFollowMode = sgElytra.add(new EnumSetting.Builder<ElytraFollowMode>()
        .name("elytra-follow-mode")
        .description("Velocity veya Firework tabanlı takip modu.")
        .defaultValue(ElytraFollowMode.Firework)
        .build());

    private enum ElytraFollowMode {
        Velocity,
        Firework
    }

    // Uçuş Hızı ve Boost
    private final Setting<Double> elytraFlySpeed = sgElytra.add(new DoubleSetting.Builder()
        .name("elytra-ucma-hizi")
        .description("Elytra ile temel takip hızı.")
        .defaultValue(1.8)
        .min(0.8)
        .max(4.0)
        .sliderRange(0.8, 4.0)
        .build());

    private final Setting<Double> elytraBoostWhenTargetFlying = sgElytra.add(new DoubleSetting.Builder()
        .name("hedef-elytra-ucuyorsa-boost")
        .description("Hedef Elytra'daysa ekstra hız çarpanı.")
        .defaultValue(1.3)
        .min(1.0)
        .max(2.5)
        .sliderRange(1.0, 2.5)
        .build());

    // Fişek Ayarları
    private final Setting<Double> elytraMinFireworkDistance = sgElytra.add(new DoubleSetting.Builder()
        .name("min-fisek-mesafesi")
        .description("Mesafe bundan fazlaysa fişek basılır.")
        .defaultValue(6)
        .min(3)
        .max(25)
        .sliderRange(3, 25)
        .build());

    private final Setting<Integer> fireworkSpamCount = sgElytra.add(new IntSetting.Builder()
        .name("fisek-spam-sayisi")
        .description("Bir seferde basılacak fişek sayısı.")
        .defaultValue(2)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .visible(() -> elytraFollowMode.get() == ElytraFollowMode.Firework)
        .build());

    private final Setting<Integer> fireworkSpamDelayMin = sgElytra.add(new IntSetting.Builder()
        .name("fisek-spam-gecikme-min")
        .description("Fişekler arası minimum ms gecikme.")
        .defaultValue(40)
        .min(20)
        .max(120)
        .sliderRange(20, 120)
        .visible(() -> elytraFollowMode.get() == ElytraFollowMode.Firework)
        .build());

    private final Setting<Integer> fireworkSpamDelayMax = sgElytra.add(new IntSetting.Builder()
        .name("fisek-spam-gecikme-max")
        .description("Fişekler arası maksimum ms gecikme (random).")
        .defaultValue(110)
        .min(50)
        .max(250)
        .sliderRange(50, 250)
        .visible(() -> elytraFollowMode.get() == ElytraFollowMode.Firework)
        .build());

    // Uzaktan Vuruş Ayarları
    private final Setting<Double> elytraMaxAttackDistance = sgElytra.add(new DoubleSetting.Builder()
        .name("max-vurus-mesafesi")
        .description("Elytra modunda otomatik vuruş yapılacak maksimum mesafe.")
        .defaultValue(20)
        .min(6)
        .max(35)
        .sliderRange(6, 35)
        .build());

    private final Setting<Integer> elytraAttackDelayMin = sgElytra.add(new IntSetting.Builder()
        .name("vurus-gecikmesi-min")
        .description("Elytra modunda vuruş arası minimum tick.")
        .defaultValue(4)
        .min(2)
        .max(12)
        .sliderRange(2, 12)
        .build());

    private final Setting<Integer> elytraAttackDelayMax = sgElytra.add(new IntSetting.Builder()
        .name("vurus-gecikmesi-max")
        .description("Elytra modunda vuruş arası maksimum tick (random aralık).")
        .defaultValue(8)
        .min(4)
        .max(18)
        .sliderRange(4, 18)
        .build());

    // Anti-Cheat Bypass Ayarları (Elytra için özel)
    private final Setting<Boolean> elytraSmoothRotation = sgElytra.add(new BoolSetting.Builder()
        .name("yumusak-donme")
        .description("Ani dönme yerine yumuşak rotasyon (anti-cheat).")
        .defaultValue(true)
        .build());

    private final Setting<Double> elytraRotationSpeedMin = sgElytra.add(new DoubleSetting.Builder()
        .name("donme-hizi-min")
        .description("Yumuşak dönme minimum hızı.")
        .defaultValue(5.0)
        .min(3)
        .max(12)
        .sliderRange(3, 12)
        .visible(elytraSmoothRotation::get)
        .build());

    private final Setting<Double> elytraRotationSpeedMax = sgElytra.add(new DoubleSetting.Builder()
        .name("donme-hizi-max")
        .description("Yumuşak dönme maksimum hızı (random aralık).")
        .defaultValue(10.0)
        .min(7)
        .max(18)
        .sliderRange(7, 18)
        .visible(elytraSmoothRotation::get)
        .build());

    private final Setting<Double> elytraRotationRandom = sgElytra.add(new DoubleSetting.Builder()
        .name("donme-random-miktar")
        .description("Dönme açısına eklenen random sapma (anti-pattern).")
        .defaultValue(0.08)
        .min(0)
        .max(0.25)
        .sliderRange(0, 0.25)
        .visible(elytraSmoothRotation::get)
        .build());

    private final Setting<Double> elytraVelocityRandom = sgElytra.add(new DoubleSetting.Builder()
        .name("velocity-random")
        .description("Hız vektörüne eklenen random miktar (anti-cheat).")
        .defaultValue(0.14)
        .min(0)
        .max(0.4)
        .sliderRange(0, 0.4)
        .build());

    private final Setting<Boolean> elytraVelocitySmoothing = sgElytra.add(new BoolSetting.Builder()
        .name("velocity-yumusatma")
        .description("Hız değişimini yumuşat (ani hızlanma önleme).")
        .defaultValue(true)
        .build());

    private final Setting<Double> elytraVelocitySmoothFactor = sgElytra.add(new DoubleSetting.Builder()
        .name("velocity-yumusatma-faktoru")
        .description("Hız yumuşatma oranı (düşük = daha yavaş değişim).")
        .defaultValue(0.94)
        .min(0.8)
        .max(0.99)
        .sliderRange(0.8, 0.99)
        .visible(elytraVelocitySmoothing::get)
        .build());

    // Güvenlik Ayarları (Elytra için)
    private final Setting<Boolean> elytraLowHealthDisable = sgElytra.add(new BoolSetting.Builder()
        .name("dusuk-can-kapat")
        .description("Can düşükse Elytra Target'ı kapat.")
        .defaultValue(true)
        .build());

    private final Setting<Double> elytraLowHealthThreshold = sgElytra.add(new DoubleSetting.Builder()
        .name("dusuk-can-esik")
        .description("Kaç kalp altında Elytra Target kapansın.")
        .defaultValue(6.0)
        .min(2.0)
        .max(12.0)
        .sliderRange(2.0, 12.0)
        .visible(elytraLowHealthDisable::get)
        .build());

    private final Setting<Boolean> elytraNoFireworkDisable = sgElytra.add(new BoolSetting.Builder()
        .name("fisek-bittiginde-kapat")
        .description("Fişek kalmayınca Elytra Target'ı kapat.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> elytraMinFireworkCount = sgElytra.add(new IntSetting.Builder()
        .name("min-kalan-fisek")
        .description("Envanterde bu kadar fişek kalırsa kapat.")
        .defaultValue(8)
        .min(3)
        .max(30)
        .sliderRange(3, 30)
        .visible(elytraNoFireworkDisable::get)
        .build());

    private final Setting<Boolean> elytraAvoidLava = sgElytra.add(new BoolSetting.Builder()
        .name("lav-kacin")
        .description("Yolda lava varsa Elytra hareketini durdur.")
        .defaultValue(true)
        .build());

    // Elytra Değişkenleri
    private final Random elytraRandom = new Random();
    private PlayerEntity elytraTarget;
    private float elytraTargetYaw = 0;
    private float elytraTargetPitch = 0;
    private int elytraTickSkip = 0;

    // Orijinal KillAura onTick (hiç dokunmadan bırakıyorum)
    @EventHandler
    private void onTick(TickEvent.Post event) {
        // ... orijinal KillAura tick kodu tamamen aynı kalıyor ...
        // (senin verdiğin kodun tamamı buraya gelecek, ben kısalttım)
    }

    // Elytra Target Tick (ayrı bir event handler olarak ekliyorum)
    @EventHandler
    private void onElytraTick(TickEvent.Post event) {
        if (!isActive()) return;

        if (mc.player == null || mc.world == null) return;

        // Güvenlik kontrolleri
        if (elytraLowHealthDisable.get() && mc.player.getHealth() < elytraLowHealthThreshold.get() * 2) {
            error("Düşük can, Elytra Target kapatılıyor.");
            toggle();
            return;
        }

        if (elytraNoFireworkDisable.get() && InvUtils.find(Items.FIREWORK_ROCKET).count() < elytraMinFireworkCount.get()) {
            error("Fişek azaldı, Elytra Target kapatılıyor.");
            toggle();
            return;
        }

        // Tick atlama (anti-pattern)
        elytraTickSkip++;
        int skip = elytraRandom.nextInt(4) + 1; // 1-5 arası random skip
        if (elytraTickSkip < skip) return;
        elytraTickSkip = 0;

        // Elytra hedef bul
        elytraTarget = TargetUtils.getPlayerTarget(searchRange.get(), priority.get());

        if (elytraTarget == null || elytraTarget == mc.player) return;

        if (ignoreFriends.get() && Friends.get().isFriend(elytraTarget)) return;
        if (ignoreInvisible.get() && elytraTarget.isInvisible()) return;

        // Elytra giyilmemişse otomatik giy
        if (!mc.player.isFallFlying() && autoEquipElytra.get()) {
            FindItemResult elytra = InvUtils.find(Items.ELYTRA);
            if (elytra.found()) {
                InvUtils.swap(elytra.slot(), false);
            }
            mc.player.jump();
            return;
        }

        // Pozisyon hesapları
        Vec3d targetPos = elytraTarget.getPos().add(0, elytraTarget.getEyeHeight(elytraTarget.getPose()), 0);
        Vec3d selfPos = mc.player.getPos().add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
        double distance = selfPos.distanceTo(targetPos);

        // Fişek basma
        if (distance > elytraMinFireworkDistance.get() && InvUtils.find(Items.FIREWORK_ROCKET).found()) {
            for (int i = 0; i < fireworkSpamCount.get(); i++) {
                useFirework();
                try {
                    Thread.sleep(fireworkSpamDelayMin.get() + elytraRandom.nextInt(fireworkSpamDelayMax.get() - fireworkSpamDelayMin.get() + 1));
                } catch (InterruptedException ignored) {}
            }
        }

        // Dönme
        if (elytraSmoothRotation.get()) {
            double dx = targetPos.x - selfPos.x;
            double dy = targetPos.y - selfPos.y;
            double dz = targetPos.z - selfPos.z;

            float wantedYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90);
            float wantedPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx + dz*dz))));

            wantedYaw += (float) (elytraRotationRandom.get() * (elytraRandom.nextDouble() * 2 - 1));
            wantedPitch += (float) (elytraRotationRandom.get() * (elytraRandom.nextDouble() * 2 - 1));

            double rotSpeed = elytraRotationSpeedMin.get() + elytraRandom.nextDouble() * (elytraRotationSpeedMax.get() - elytraRotationSpeedMin.get());

            elytraTargetYaw = (float) Rotations.rotate(elytraTargetYaw, wantedYaw, (int) rotSpeed);
            elytraTargetPitch = (float) Rotations.rotate(elytraTargetPitch, wantedPitch, (int) rotSpeed);

            Rotations.rotate(elytraTargetYaw, elytraTargetPitch, null);
        }

        // Hareket
        Vec3d direction = new Vec3d(targetPos.x - selfPos.x, targetPos.y - selfPos.y, targetPos.z - selfPos.z).normalize().multiply(elytraFlySpeed.get());

        if (elytraTarget.isFallFlying()) {
            direction = direction.multiply(elytraBoostWhenTargetFlying.get());
        }

        if (elytraVelocityRandom.get() > 0) {
            direction = direction.add(
                elytraRandom.nextDouble() * elytraVelocityRandom.get() * 2 - elytraVelocityRandom.get(),
                elytraRandom.nextDouble() * elytraVelocityRandom.get() * 2 - elytraVelocityRandom.get(),
                elytraRandom.nextDouble() * elytraVelocityRandom.get() * 2 - elytraVelocityRandom.get()
            );
        }

        if (elytraVelocitySmoothing.get()) {
            Vec3d current = mc.player.getVelocity();
            direction = current.multiply(0.92).add(direction.multiply(0.08));
        }

        mc.player.setVelocity(direction);

        // Elytra modunda otomatik vuruş
        if (distance <= elytraMaxAttackDistance.get()) {
            int delay = elytraRandom.nextInt(elytraAttackDelayMax.get() - elytraAttackDelayMin.get() + 1) + elytraAttackDelayMin.get();
            if (elytraTickSkip % delay == 0) {
                mc.interactionManager.attackEntity(mc.player, elytraTarget);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    private void useFirework() {
        FindItemResult firework = InvUtils.find(Items.FIREWORK_ROCKET);
        if (!firework.found()) return;

        int slot = firework.slot();
        int oldSlot = mc.player.getInventory().selectedSlot;

        mc.player.getInventory().selectedSlot = slot;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        mc.player.getInventory().selectedSlot = oldSlot;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
    }

    // Orijinal KillAura kodunun geri kalan kısmı buraya dokunulmadan kalıyor...
    // (PacketEvent, diğer ayarlar, attack logic vs. tamamen aynı kalır)
    }
