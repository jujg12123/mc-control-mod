package com.xt.mccontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.xt.mccontrol.ActionExecutor.*;

/**
 * 从 ActionExecutor 拆出的独立任务类（SmoothLookTask）。
 */
final class SmoothLookTask implements ActionTask {
        private final float targetYaw;
        private final float targetPitch;
        private final long myVersion;
        private final long callId = currentCallId;
        private int totalTicks = 0;
        private static final int MAX_TICKS = 100; // 5 秒超时

        SmoothLookTask(float yaw, float pitch) {
            this.targetYaw = yaw;
            this.targetPitch = pitch;
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                return true;
            }

            totalTicks++;
            if (totalTicks > MAX_TICKS) {
                // 超时，直接到位
                player.setYaw(targetYaw);
                player.setPitch(targetPitch);
                sendResult("look_at", callId, true, "视角已调整 (超时强制到位)");
                return true;
            }

            float currentYaw = player.getYaw();
            float currentPitch = player.getPitch();

            // 计算最短角度差（处理 360° 环绕）
            float yawDelta = MathHelper.wrapDegrees(targetYaw - currentYaw);
            float pitchDelta = targetPitch - currentPitch;

            // 角度差足够小，直接到位
            if (Math.abs(yawDelta) < 1.0f && Math.abs(pitchDelta) < 1.0f) {
                player.setYaw(targetYaw);
                player.setPitch(targetPitch);
                sendResult("look_at", callId, true,
                    String.format("视角已调整 (yaw=%.1f, pitch=%.1f, %d ticks)",
                        targetYaw, targetPitch, totalTicks));
                return true;
            }

            // 获取鼠标灵敏度（0.0 ~ 1.0，默认 0.5）
            // 1.20.1 中 mouseSensitivity 是私有字段，用反射读取
            double sensitivity = 0.5;
            try {
                java.lang.reflect.Field f = client.options.getClass()
                        .getDeclaredField("mouseSensitivity");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                net.minecraft.client.option.SimpleOption<Double> opt =
                        (net.minecraft.client.option.SimpleOption<Double>) f.get(client.options);
                sensitivity = opt.getValue();
            } catch (Exception e) {
                // 反射失败时使用默认灵敏度 0.5
            }
            // Minecraft 原版鼠标灵敏度公式
            double f = sensitivity * 0.6 + 0.2;
            double anglePerPixel = f * f * f * 8.0 * 0.15;

            // 量化为鼠标像素并换算回角度（模拟真实鼠标输入）
            float yawStep = stepToward(yawDelta, anglePerPixel);
            float pitchStep = stepToward(pitchDelta, anglePerPixel);

            // 限制每 tick 最大转角（25°/tick ≈ 500°/秒，自然但不过慢）
            yawStep = MathHelper.clamp(yawStep, -25.0f, 25.0f);
            pitchStep = MathHelper.clamp(pitchStep, -25.0f, 25.0f);

            player.setYaw(currentYaw + yawStep);
            player.setPitch(MathHelper.clamp(currentPitch + pitchStep, -90.0f, 90.0f));

            return false;
        }

        /**
         * 将角度差量化为鼠标像素，再换算回角度步长。
         * 这模拟了真实鼠标的离散输入特性。
         */
        private float stepToward(float delta, double anglePerPixel) {
            if (anglePerPixel <= 0) return delta; // 防除零
            int pixels = Math.round(delta / (float) anglePerPixel);
            if (pixels == 0) {
                // 小于一个像素的角度差，直接返回完整 delta（下个 tick 到位）
                return delta;
            }
            return pixels * (float) anglePerPixel;
        }
}
