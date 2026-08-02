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
 * 从 ActionExecutor 拆出的独立任务类（CraftTask）。
 */
final class CraftTask implements ActionTask {
        private final RecipeLookup.RecipeInfo recipe;
        private final int count;
        private final long myVersion;
        private final BlockPos tablePos;     // null = 背包合成, 非null = 工作台合成
        private final int beforeCount;       // 合成前背包中该物品的数量
        private final String outputId;       // 产物物品 ID（短名）
        private final long callId = currentCallId;

        // 状态机阶段
        // 0=打开界面, 1=等待界面打开, 2=填充网格, 21=手动摆放材料,
        // 3=等待填充完成, 4=确保光标空闲, 5=取出产物, 6=等待取出完成,
        // 7=检查是否需要继续合成(循环), 8=关闭并报告
        private int phase = 0;
        private int phaseTicks = 0;
        private int craftsDone = 0;           // 已完成的合成次数
        private int slotCooldown = 0;         // 槽位操作冷却（tick）

        // 手动填充模式：clickRecipe 依赖配方书解锁，服务端对未解锁配方静默忽略。
        // 检测到无产物时回退为逐格 clickSlot 手动摆放，任何配方都可靠。
        private boolean manualMode = false;
        private final List<Integer> manualCells = new ArrayList<>();          // 网格槽位（屏幕坐标）
        private final List<Ingredient> manualIngredients = new ArrayList<>(); // 对应材料
        private int fillStep = 0;          // 手动填充进度
        private int pendingGridSlot = -1;  // 光标已拿起材料，等待放入的网格槽位

        CraftTask(RecipeLookup.RecipeInfo recipe, int count, BlockPos tablePos,
                  int beforeCount, String outputId) {
            this.recipe = recipe;
            this.count = count;
            this.tablePos = tablePos;
            this.beforeCount = beforeCount;
            this.outputId = outputId;
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                player.closeHandledScreen();
                return true;
            }

            // 槽位操作冷却
            if (slotCooldown > 0) {
                slotCooldown--;
                return false;
            }

            switch (phase) {
                case 0: { // 打开界面
                    if (tablePos != null) {
                        // 转向工作台并右键交互
                        double dx = tablePos.getX() + 0.5 - player.getX();
                        double dy = tablePos.getY() + 0.5
                                - (player.getY() + player.getEyeHeight(player.getPose()));
                        double dz = tablePos.getZ() + 0.5 - player.getZ();
                        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                        player.setPitch((float) Math.toDegrees(
                                -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

                        HitResult hit = player.raycast(5.0, 0, false);
                        if (hit.getType() == HitResult.Type.BLOCK) {
                            client.interactionManager.interactBlock(
                                    player, Hand.MAIN_HAND, (BlockHitResult) hit);
                        }
                        phase = 1;
                        phaseTicks = 0;
                    } else {
                        // 背包合成：直接打开背包界面
                        client.setScreen(new InventoryScreen(player));
                        phase = 2;
                        phaseTicks = 0;
                    }
                    slotCooldown = 2;
                    return false;
                }
                case 1: { // 等待工作台界面打开
                    phaseTicks++;
                    if (client.currentScreen != null) {
                        phase = 2;
                        phaseTicks = 0;
                    } else if (phaseTicks > 20) { // 1 秒超时
                        sendResult("craft", callId, false, "无法打开工作台界面，可能距离太远");
                        return true;
                    }
                    return false;
                }
                case 2: { // 填充合成网格（clickRecipe 或手动摆放）
                    if (manualMode) {
                        phase = 21;
                        phaseTicks = 0;
                        fillStep = 0;
                        pendingGridSlot = -1;
                        slotCooldown = 1;
                        return false;
                    }
                    try {
                        int syncId = player.currentScreenHandler.syncId;
                        // craftAll=false: 只合成一次，不把所有材料都消耗掉
                        client.interactionManager.clickRecipe(syncId, recipe.recipe, false);
                    } catch (Exception e) {
                        player.closeHandledScreen();
                        sendResult("craft", callId, false, "填充合成网格失败: " + e.getMessage());
                        return true;
                    }
                    phase = 3;
                    phaseTicks = 0;
                    slotCooldown = 3; // 等待服务端处理
                    return false;
                }
                case 21: { // 手动摆放材料（每个 tick 最多一次点击，避免服务端丢包）
                    if (manualCells.isEmpty()) {
                        if (!initManualPlacements()) {
                            player.closeHandledScreen();
                            sendResult("craft", callId, false, "该配方无法自动摆放，请手动合成");
                            return true;
                        }
                    }
                    // 第二步：把光标中的材料放入网格
                    if (pendingGridSlot >= 0) {
                        client.interactionManager.clickSlot(
                                player.currentScreenHandler.syncId, pendingGridSlot, 0,
                                SlotActionType.PICKUP, player);
                        pendingGridSlot = -1;
                        fillStep++;
                        slotCooldown = 1;
                        return false;
                    }
                    // 全部摆放完成，等待服务端生成产物
                    if (fillStep >= manualCells.size()) {
                        phase = 3;
                        phaseTicks = 0;
                        slotCooldown = 3;
                        return false;
                    }
                    int gridSlot = manualCells.get(fillStep);
                    Ingredient ing = manualIngredients.get(fillStep);
                    // 该格已有匹配材料（上一轮残留），跳过
                    ItemStack gridStack = player.currentScreenHandler.getSlot(gridSlot).getStack();
                    if (!gridStack.isEmpty() && ing.test(gridStack)) {
                        fillStep++;
                        return false;
                    }
                    int invStart = (tablePos != null) ? 10 : 9;
                    int invEnd = (tablePos != null) ? 46 : 45;
                    for (int i = invStart; i < invEnd; i++) {
                        ItemStack s = player.currentScreenHandler.getSlot(i).getStack();
                        if (!s.isEmpty() && ing.test(s)) {
                            // 第一步：从背包拿起材料
                            client.interactionManager.clickSlot(
                                    player.currentScreenHandler.syncId, i, 0,
                                    SlotActionType.PICKUP, player);
                            pendingGridSlot = gridSlot;
                            slotCooldown = 1;
                            return false;
                        }
                    }
                    // 材料不足（可能已被前几轮消耗）
                    player.closeHandledScreen();
                    String ingDesc = "未知材料";
                    ItemStack[] matchStacks = ing.getMatchingStacks();
                    if (matchStacks.length > 0) ingDesc = matchStacks[0].getName().getString();
                    sendResult("craft", callId, false, "合成材料不足: " + ingDesc);
                    return true;
                }
                case 3: { // 等待网格填充完成
                    phaseTicks++;
                    int waitTicks = manualMode ? 6 : 3; // 手动摆放时给服务端更多同步时间
                    if (phaseTicks > waitTicks) {
                        // 检查产物槽是否有物品
                        try {
                            ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                            if (output.isEmpty()) {
                                // clickRecipe 无产物：常见原因是配方书未解锁（服务端静默忽略），
                                // 回退为逐格手动摆放
                                if (!manualMode && isManuallyPlaceable()) {
                                    manualMode = true;
                                    phase = 2;
                                    phaseTicks = 0;
                                    slotCooldown = 2;
                                    return false;
                                }
                                player.closeHandledScreen();
                                if (craftsDone > 0) {
                                    reportCraftResult(player, true);
                                    return true;
                                }
                                sendResult("craft", callId, false,
                                    "合成失败: 配方与当前合成台不匹配，或材料已被消耗");
                                return true;
                            }
                        } catch (Exception e) {
                            // 忽略检查错误，继续尝试取出
                        }
                        phase = 4;
                        phaseTicks = 0;
                    }
                    return false;
                }
                case 4: { // 确保光标空闲（借鉴 Altoclef EnsureFreeCursorSlotTask）
                    ItemStack cursor = player.currentScreenHandler.getCursorStack();
                    if (cursor.isEmpty()) {
                        phase = 5;
                        return false;
                    }
                    // 检查光标物品是否与产物相同（可叠加）
                    ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                    if (!output.isEmpty() && ItemStack.areItemsEqual(cursor, output)
                            && cursor.getCount() < cursor.getMaxCount()) {
                        // 光标物品与产物相同且可叠加，可直接取出
                        phase = 5;
                        return false;
                    }
                    // 光标有不同物品，尝试放入背包空槽
                    // 背包槽位：工作台 10-36，背包 5-35（跳过护甲）
                    int invStart = (tablePos != null) ? 10 : 9;
                    int invEnd = (tablePos != null) ? 46 : 45;
                    for (int i = invStart; i < invEnd; i++) {
                        try {
                            ItemStack slot = player.currentScreenHandler.getSlot(i).getStack();
                            if (slot.isEmpty()) {
                                // 点击空槽放入光标物品
                                client.interactionManager.clickSlot(
                                        player.currentScreenHandler.syncId, i, 0,
                                        SlotActionType.PICKUP, player);
                                slotCooldown = 2;
                                phase = 5;
                                return false;
                            }
                        } catch (Exception e) {
                            break;
                        }
                    }
                    // 背包满了，无法清空光标，用 THROW 丢弃
                    client.interactionManager.clickSlot(
                            player.currentScreenHandler.syncId, -999, 0,
                            SlotActionType.PICKUP, player);
                    slotCooldown = 2;
                    phase = 5;
                    return false;
                }
                case 5: { // 取出产物（shift-click 转移到背包，每次只取一次合成结果）
                    ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                    if (output.isEmpty()) {
                        // 产物槽空了，可能已取出
                        phase = 7;
                        return false;
                    }

                    int syncId = player.currentScreenHandler.syncId;
                    // shift-click 将产物槽的物品转移到背包
                    client.interactionManager.clickSlot(
                            syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
                    phase = 6;
                    phaseTicks = 0;
                    slotCooldown = 3;
                    return false;
                }
                case 6: { // 等待产物取出完成
                    phaseTicks++;
                    if (phaseTicks > 3) {
                        craftsDone++;
                        phase = 7;
                        phaseTicks = 0;
                    }
                    return false;
                }
                case 7: { // 检查是否需要继续合成
                    if (craftsDone >= count) {
                        // 已合成足够数量
                        phase = 8;
                        return false;
                    }
                    // 检查产物槽是否还有物品（说明材料还够再合成一次）
                    try {
                        ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                        if (!output.isEmpty()) {
                            // 还有产物，继续取出
                            phase = 4;
                            return false;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    // 产物槽空了，需要重新填充网格
                    phase = 2;
                    return false;
                }
                case 8: { // 关闭界面并报告结果
                    player.closeHandledScreen();
                    reportCraftResult(player, craftsDone > 0);
                    return true;
                }
            }
            return false;
        }

        /** 该配方是否支持手动摆放（有序/无序合成） */
        private boolean isManuallyPlaceable() {
            return recipe.recipe instanceof ShapedRecipe || recipe.recipe instanceof ShapelessRecipe;
        }

        /**
         * 计算手动摆放方案：网格槽位（屏幕坐标，工作台 1-9 / 背包 1-4）+ 对应材料。
         * 有序合成按 pattern 逐格映射，无序合成按顺序填入空格。
         */
        private boolean initManualPlacements() {
            manualCells.clear();
            manualIngredients.clear();
            DefaultedList<Ingredient> ings = recipe.recipe.getIngredients();
            int gridW = (tablePos != null) ? 3 : 2;
            if (recipe.recipe instanceof ShapedRecipe shaped) {
                // Yarn 1.20.1 的 ShapedRecipe 没有无参 getPattern()，
                // 直接用 width/height 按行主序遍历 ingredients（含空位），等价于 pattern 摆放
                int width = shaped.getWidth();
                int height = shaped.getHeight();
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        int idx = row * width + col;
                        if (idx >= ings.size() || ings.get(idx).isEmpty()) continue;
                        manualCells.add(row * gridW + col + 1);
                        manualIngredients.add(ings.get(idx));
                    }
                }
            } else if (recipe.recipe instanceof ShapelessRecipe) {
                int cell = 1;
                int maxCells = gridW * gridW;
                for (Ingredient ing : ings) {
                    if (ing.isEmpty()) continue;
                    if (cell > maxCells) break;
                    manualCells.add(cell);
                    manualIngredients.add(ing);
                    cell++;
                }
            } else {
                return false;
            }
            return !manualCells.isEmpty();
        }

        private void reportCraftResult(ClientPlayerEntity player, boolean success) {
            PlayerInventory inv = player.getInventory();
            int afterCount = countItemByIngredient(inv, Set.of(outputId));
            int crafted = afterCount - beforeCount;

            if (crafted > 0) {
                String msg = "合成成功: " + recipe.outputName + " ×" + crafted
                        + " (配方: " + recipe.type + ", 站: " + recipe.station + ")";
                if (crafted < count * recipe.outputCount) {
                    msg += " (请求 " + count + " 次, 实际合成 "
                            + (crafted / Math.max(1, recipe.outputCount)) + " 次, 材料不足)";
                }
                sendResult("craft", callId, true, msg);
            } else if (success) {
                sendResult("craft", callId, true,
                    "合成完成但未检测到新增物品 (可能背包已有该物品)");
            } else {
                sendResult("craft", callId, false,
                    "合成可能失败: 未检测到新增物品。请检查材料是否足够或配方是否正确。");
            }
        }
}
