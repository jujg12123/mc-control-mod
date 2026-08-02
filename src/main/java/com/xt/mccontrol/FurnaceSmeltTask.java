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
 * 从 ActionExecutor 拆出的独立任务类（FurnaceSmeltTask）。
 */
final class FurnaceSmeltTask implements ActionTask {
        private static final int FIND = 0, OPEN = 1, PUT_INPUT = 2, PUT_FUEL = 3,
                SMELT = 4, TAKE = 5, DONE = 6;
        private final long myVersion;
        private final long callId = currentCallId;
        private final RecipeLookup.RecipeInfo recipe;
        private final BlockPos furnacePos;
        private final String outputId;
        private final int needInput;      // 需要的原料总数
        private final String inputDisplay;
        private final String furnaceDisplay;
        private int phase = FIND;
        private int phaseTicks = 0;
        private int slotCooldown = 0;
        private int putCount = 0;         // 连续点击计数（防止卡死）
        private int inputInvSlot = -1;    // 原料在背包的槽位
        private int fuelInvSlot = -1;     // 燃料在背包的槽位

        FurnaceSmeltTask(RecipeLookup.RecipeInfo recipe, int count, BlockPos furnacePos) {
            this.myVersion = actionVersion;
            this.recipe = recipe;
            this.furnacePos = furnacePos;
            this.outputId = recipe.outputItemId.contains(":")
                    ? recipe.outputItemId.substring(recipe.outputItemId.indexOf(':') + 1)
                    : recipe.outputItemId;
            List<RecipeLookup.RequiredIngredient> req = RecipeLookup.getRequiredIngredients(recipe);
            int per = 1;
            for (RecipeLookup.RequiredIngredient r : req) per = Math.max(per, r.count);
            this.needInput = Math.max(1, per * count);
            this.inputDisplay = req.isEmpty() ? "原料" : req.get(0).displayName;
            if ("blasting".equals(recipe.type)) this.furnaceDisplay = "高炉";
            else if ("smoking".equals(recipe.type)) this.furnaceDisplay = "烟熏炉";
            else this.furnaceDisplay = "熔炉";
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                releaseAllKeys(client);
                player.closeHandledScreen();
                return true;
            }
            if (slotCooldown > 0) slotCooldown--;

            switch (phase) {
                case FIND: {   // 靠近熔炉并打开界面
                    phaseTicks++;
                    double dx = furnacePos.getX() + 0.5 - player.getX();
                    double dz = furnacePos.getZ() + 0.5 - player.getZ();
                    double hDist = Math.sqrt(dx * dx + dz * dz);
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    player.setPitch(0);
                    if (hDist > 3.2) {
                        client.options.forwardKey.setPressed(true);
                        if (phaseTicks > 200) {   // 10 秒走不到：放弃
                            client.options.forwardKey.setPressed(false);
                            sendResult("craft", callId, false,
                                    "无法靠近" + furnaceDisplay + "（" + furnacePos.toShortString()
                                            + "），请先走到炉子旁边再调用 mc_craft");
                            return true;
                        }
                        return false;
                    }
                    client.options.forwardKey.setPressed(false);
                    if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
                        phase = PUT_INPUT;
                        phaseTicks = 0;
                        return false;
                    }
                    Vec3d hitPos = new Vec3d(furnacePos.getX() + 0.5,
                            furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5);
                    BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, furnacePos, false);
                    client.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
                    phase = OPEN;
                    phaseTicks = 0;
                    return false;
                }
                case OPEN: {   // 等待界面打开
                    phaseTicks++;
                    if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
                        phase = PUT_INPUT;
                        phaseTicks = 0;
                        return false;
                    }
                    if (phaseTicks > 40) {
                        sendResult("craft", callId, false, "打开" + furnaceDisplay + "界面超时，请检查炉子位置");
                        return true;
                    }
                    return false;
                }
                case PUT_INPUT: {
                    phaseTicks++;
                    if (slotCooldown > 0) return false;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = FIND;   // 界面丢了：重新打开
                        phaseTicks = 0;
                        return false;
                    }
                    int cur = handler.getSlot(0).getStack().getCount();
                    if (cur >= needInput) {
                        phase = PUT_FUEL;
                        phaseTicks = 0;
                        putCount = 0;
                        return false;
                    }
                    if (inputInvSlot < 0) {
                        inputInvSlot = findSmeltInputSlot(player);
                        if (inputInvSlot < 0) {
                            sendResult("craft", callId, false,
                                    "背包中没有烧炼原料: " + inputDisplay + "（需要 " + needInput + " 个）");
                            player.closeHandledScreen();
                            return true;
                        }
                    }
                    // shift-click 把原料批量放入输入槽
                    client.interactionManager.clickSlot(handler.syncId,
                            screenSlotOf(inputInvSlot), 0, SlotActionType.QUICK_MOVE, player);
                    slotCooldown = 3;
                    if (++putCount >= 12) {
                        sendResult("craft", callId, false,
                                "原料无法放入" + furnaceDisplay + "，请检查原料是否正确（" + inputDisplay + "）");
                        player.closeHandledScreen();
                        return true;
                    }
                    return false;
                }
                case PUT_FUEL: {
                    phaseTicks++;
                    if (slotCooldown > 0) return false;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = FIND;
                        phaseTicks = 0;
                        return false;
                    }
                    // 燃料槽已有燃料或正在燃烧：开始等待
                    if (!handler.getSlot(1).getStack().isEmpty() || handler.isBurning()) {
                        phase = SMELT;
                        phaseTicks = 0;
                        putCount = 0;
                        return false;
                    }
                    if (fuelInvSlot < 0) {
                        fuelInvSlot = findFuelSlot(player);
                        if (fuelInvSlot < 0) {
                            sendResult("craft", callId, false,
                                    "背包中没有燃料（煤炭/木炭/木板/原木等）。请先收集燃料再烧炼。");
                            player.closeHandledScreen();
                            return true;
                        }
                    }
                    client.interactionManager.clickSlot(handler.syncId,
                            screenSlotOf(fuelInvSlot), 0, SlotActionType.QUICK_MOVE, player);
                    slotCooldown = 3;
                    if (++putCount >= 12) {
                        sendResult("craft", callId, false, "燃料无法放入" + furnaceDisplay);
                        player.closeHandledScreen();
                        return true;
                    }
                    return false;
                }
                case SMELT: {   // 等待烧制完成
                    phaseTicks++;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        sendResult("craft", callId, false, furnaceDisplay + "界面意外关闭");
                        return true;
                    }
                    // 输出槽有产物：取出
                    if (!handler.getSlot(2).getStack().isEmpty()) {
                        phase = TAKE;
                        phaseTicks = 0;
                        return false;
                    }
                    // 燃料耗尽且还没有产物：自动补燃料
                    if (handler.getSlot(1).getStack().isEmpty()
                            && !handler.getSlot(0).getStack().isEmpty()
                            && phaseTicks % 60 == 0) {
                        fuelInvSlot = findFuelSlot(player);
                        if (fuelInvSlot >= 0) {
                            client.interactionManager.clickSlot(handler.syncId,
                                    screenSlotOf(fuelInvSlot), 0, SlotActionType.QUICK_MOVE, player);
                            slotCooldown = 3;
                            StateCollector.addBehaviorLog("熔炉燃料耗尽，自动补充");
                        } else {
                            sendResult("craft", callId, false,
                                    "燃料烧完了且背包没有燃料，已停止烧炼");
                            player.closeHandledScreen();
                            return true;
                        }
                    }
                    // 超时 120 秒
                    if (phaseTicks > 2400) {
                        sendResult("craft", callId, false, "烧炼超时，请检查" + furnaceDisplay + "是否有燃料");
                        player.closeHandledScreen();
                        return true;
                    }
                    return false;
                }
                case TAKE: {
                    phaseTicks++;
                    if (slotCooldown > 0) return false;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = FIND;
                        phaseTicks = 0;
                        return false;
                    }
                    client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
                    slotCooldown = 2;
                    phase = DONE;
                    phaseTicks = 0;
                    return false;
                }
                default: { // DONE
                    phaseTicks++;
                    if (phaseTicks > 6) {
                        player.closeHandledScreen();
                        int have = countItemByIngredient(player.getInventory(), Set.of(outputId));
                        sendResult("craft", callId, true,
                                "烧炼成功: " + outputId + "（背包现有 " + have + " 个）");
                        return true;
                    }
                    return false;
                }
            }
        }

        /** 从背包找烧炼原料槽位（匹配配方输入） */
        private int findSmeltInputSlot(ClientPlayerEntity player) {
            List<RecipeLookup.RequiredIngredient> req = RecipeLookup.getRequiredIngredients(recipe);
            if (req.isEmpty()) return -1;
            Set<String> matchIds = req.get(0).matchIds;
            PlayerInventory inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                Identifier id = Registries.ITEM.getId(s.getItem());
                if (id != null && matchIds.contains(id.getPath())) return i;
            }
            return -1;
        }

        /**
         * 从背包找燃料槽位。
         * Yarn 1.20.1 没有公开的 Item#getFuelTime()（那是 Mojang 映射 API），
         * 这里用熔炉燃料槽的 Slot#canInsert() 判断（原版逻辑：isFuel || 岩浆桶，
         * 兼容模组燃料）；界面未打开时回退到常见燃料硬编码判断。
         */
        private int findFuelSlot(ClientPlayerEntity player) {
            PlayerInventory inv = player.getInventory();
            boolean furnaceOpen = player.currentScreenHandler instanceof AbstractFurnaceScreenHandler;
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                if (furnaceOpen) {
                    // 熔炉界面已打开：用燃料槽的插入规则精确判断
                    if (((AbstractFurnaceScreenHandler) player.currentScreenHandler)
                            .getSlot(1).canInsert(s)) {
                        return i;
                    }
                } else if (isCommonFuel(s)) {
                    return i;
                }
            }
            return -1;
        }

        /** 常见燃料硬编码判断（界面未打开时的兜底） */
        private static boolean isCommonFuel(ItemStack stack) {
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id == null) return false;
            String p = id.getPath().toLowerCase();
            return p.contains("coal") || p.contains("charcoal")
                    || p.contains("_log") || p.contains("_wood") || p.contains("_planks")
                    || p.contains("_stem") || p.contains("_hyphae") || p.contains("_sapling")
                    || p.contains("_fence") || p.contains("_fence_gate") || p.contains("_door")
                    || p.contains("_slab") || p.contains("_stairs") || p.contains("_pressure_plate")
                    || p.contains("_button") || p.contains("torch") || p.contains("_sign")
                    || p.contains("_boat") || p.contains("ladder") || p.contains("bookshelf")
                    || p.contains("crafting_table") || p.contains("chest") || p.contains("_barrel")
                    || p.contains("lava_bucket") || p.contains("blaze_rod")
                    || p.contains("dried_kelp_block") || p.contains("sugar_cane")
                    || p.contains("cactus") || p.contains("scaffolding") || p.contains("stick")
                    || p.equals("bamboo") || p.equals("bowl");
        }

        /** 背包槽位 → 熔炉界面屏幕槽位 */
        private int screenSlotOf(int invSlot) {
            if (invSlot < 9) return invSlot + 30;   // 快捷栏 0-8 → 30-38
            return invSlot - 9 + 3;                 // 背包 9-35 → 3-29
        }
}
