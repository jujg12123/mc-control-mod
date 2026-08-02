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
 * 从 ActionExecutor 拆出的独立任务类（DigDownTask）。
 */
final class DigDownTask implements ActionTask {
        private final int distance;
        private final long myVersion;
        private final long callId = currentCallId;
        private int current = 0;     // 已完成的格数
        private int phase = 0;       // 0=检查并开始挖, 1=等待挖完, 2=潜行下移
        private int phaseTicks = 0;
        private BlockPos digPos;

        DigDownTask(int distance) {
            this.distance = distance;
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                client.options.attackKey.setPressed(false);
                client.options.sneakKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                return true;
            }
            if (current >= distance) {
                sendResult("dig_down", callId, true, "已向下挖掘 " + distance + " 格");
                return true;
            }

            switch (phase) {
                case 0: { // 检查安全 + 开始挖
                    BlockPos pos = player.getBlockPos();
                    digPos = pos.down(current + 1);
                    BlockState state = player.getWorld().getBlockState(digPos);
                    String name = state.getBlock().getName().getString().toLowerCase();
                    if (name.contains("lava") || name.contains("water")) {
                        sendResult("dig_down", callId, false, "遇到危险: " + name);
                        return true;
                    }
                    // 向下看并按住挖掘键（自动切工具）
                    ensureBestTool(player, state);
                    player.setPitch(90f);
                    client.options.attackKey.setPressed(true);
                    phaseTicks = 0;
                    phase = 1;
                    return false;
                }
                case 1: { // 等待方块破坏
                    phaseTicks++;
                    if (player.getWorld().getBlockState(digPos).isAir()) {
                        client.options.attackKey.setPressed(false);
                        // 潜行下移，防止直接坠落
                        client.options.sneakKey.setPressed(true);
                        client.options.forwardKey.setPressed(true);
                        phaseTicks = 0;
                        phase = 2;
                    } else if (phaseTicks > 100) { // 5 秒超时
                        client.options.attackKey.setPressed(false);
                        sendResult("dig_down", callId, false, "挖掘超时");
                        return true;
                    }
                    return false;
                }
                case 2: { // 下移
                    phaseTicks++;
                    if (phaseTicks > 8) { // ~0.4 秒
                        client.options.forwardKey.setPressed(false);
                        client.options.sneakKey.setPressed(false);
                        current++;
                        phase = 0;
                    }
                    return false;
                }
            }
            return false;
        }
}
