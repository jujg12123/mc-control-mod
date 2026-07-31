const { Plugin } = require('../../../js/core/plugin-base.js');
const MCClient = require('./mc-client.js');

/**
 * Minecraft 控制插件。
 *
 * 修复要点（对照 My Neuro SDK 真实 API）：
 * - #5 triggerAI：SDK 的真实方法是 context.sendMessage(content)，走完整 sendToLLM 流程。
 *   不再盲猜 8 个方法名。
 * - #14 goalLoop：用 context.addSystemPromptPatch(id, text) 持续注入游戏状态到系统提示词
 *   （不进对话历史、不触发 TTS），而非每 4s 用 sendMessage 污染对话历史导致 AI 自言自语。
 *   goalLoop 仍用 sendMessage 触发 AI "继续任务"，但频率受控且有去重。
 * - #6 sendActionAndWait：用 Map<callId, {resolve, timer}> 管理多个并发待处理调用，
 *   模组回传的 action_result 带 action 名，按 callId 精确匹配，避免结果丢失。
 */
class MCControlPlugin extends Plugin {
    constructor(metadata, context) {
        super(metadata, context);
        // 所有实例字段在构造函数中初始化，确保在任何回调触发前就已就绪
        this.mc = null;
        this.latestState = null;
        this.goal = null;
        this.goalLoop = null;
        this.noCommandCount = 0;
        this.aiThinking = false;
        this.lastGoalTriggerTime = 0;
        // #6: 多 pending 调用管理
        this._callCounter = 0;
        this._pendingCalls = new Map(); // callId -> { resolve, timer, action }
    }

    async onStart() {
        const cfg = this.context.getPluginConfig();
        this.mc = new MCClient(cfg.ws_url || 'ws://127.0.0.1:8765');

        // 设置回调
        this.mc.onState = (state) => {
            this.latestState = state;
            this._refreshSystemPrompt();
        };
        this.mc.onActionResult = (result) => this._handleActionResult(result);

        // 尝试连接（失败时自动重连）
        try {
            await this.mc.connect();
            this.context.log('info', '已连接 Minecraft 客户端');
        } catch (e) {
            this.context.log('error', '初始连接失败: ' + e.message + '，将自动重连...');
        }

        const autoControl = cfg.auto_control !== undefined ? cfg.auto_control : false;
        if (autoControl) {
            setTimeout(() => {
                this.setGoal('在 Minecraft 中生存和发展：收集资源、建造庇护所、探索世界');
            }, 3000);
        }
    }

    async onStop() {
        this.stopGoal();
        this.context.removeSystemPromptPatch('mc-state');
        if (this.mc) this.mc.disconnect();
    }

    // ===== 消息拦截：注入 MC 状态到上下文 =====
    async onUserInput(event) {
        this.aiThinking = false;
        if (!this.mc || !this.mc.connected) return;

        const msg = event.text || '';

        // 有活跃目标时，始终注入游戏状态（addContext 只对本次请求生效，不进历史）
        if (this.goal) {
            const state = this.mc.getState();
            if (state) {
                const stateText = this.mc.stateToText(state);
                event.addContext('\n【Minecraft 当前状态】\n' + stateText +
                    '\n【当前任务目标】' + this.goal + '\n');
            }
            return;
        }

        // 无目标时，仅在 MC 相关关键词时注入
        const mcKeywords = ['mc', '我的世界', 'minecraft', '挖矿', '砍树', '建造', '合成', '背包', '方块', '挖', '矿'];
        const isMCRelated = mcKeywords.some(k => msg.toLowerCase().includes(k.toLowerCase()));
        if (isMCRelated) {
            const stateText = this.mc.stateToText(this.mc.getState());
            event.addContext('\n【Minecraft 当前状态】\n' + stateText + '\n');
        }
    }

    // ===== LLM 请求拦截：MC 连接时移除冲突工具 =====

    /**
     * 当 MC 已连接时，从 tools 列表中移除会与 MC 控制冲突的工具
     * （execute_code、type_text、mouse_click 等），强制 AI 只能用 mc_ 工具。
     */
    async onLLMRequest(request) {
        if (!this.mc || !this.mc.connected) return;
        if (!request || !request.tools) return;

        // 需要屏蔽的工具名（按键/鼠标/代码执行类，会和 MC 控制打架）
        const blockedTools = [
            'execute_code', 'type_text', 'mouse_click', 'mouse_move',
            'press_key', 'screenshot', 'keyboard'
        ];

        const before = request.tools.length;
        request.tools = request.tools.filter(t => {
            const name = t?.function?.name || '';
            return !blockedTools.includes(name);
        });
        const removed = before - request.tools.length;
        if (removed > 0) {
            console.log(`[MC-Control] 已屏蔽 ${removed} 个冲突工具（MC 连接中）`);
        }
    }

    // ===== 注册 AI 工具 =====
    getTools() {
        return [
            { type: 'function', function: { name: 'mc_look', description: '查看 Minecraft 当前状态：位置、视线、背包、脚下', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_move', description: '控制角色移动', parameters: { type: 'object', properties: { direction: { type: 'string', enum: ['forward', 'back', 'left', 'right'], description: '移动方向' }, duration: { type: 'number', description: '移动时长（秒），默认 0.5', default: 0.5 } }, required: ['direction'] } } },
            { type: 'function', function: { name: 'mc_dig', description: '挖掘/攻击视线前方的方块，默认持续 2 秒', parameters: { type: 'object', properties: { duration: { type: 'number', description: '挖掘时长（秒），木头约 2s，石头约 3-5s', default: 2.0 } }, required: [] } } },
            { type: 'function', function: { name: 'mc_place', description: '在视线前方放置方块。可指定物品名称自动切换到对应槽位，不指定则放置当前手持物品', parameters: { type: 'object', properties: { item_name: { type: 'string', description: '要放置的物品名称或 ID，如 crafting_table, dirt, oak_planks。不传则放置当前手持物品' } }, required: [] } } },
            { type: 'function', function: { name: 'mc_jump', description: '跳跃', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_switch', description: '切换快捷栏物品', parameters: { type: 'object', properties: { slot: { type: 'integer', description: '槽位号 (0-8)' } }, required: ['slot'] } } },
            { type: 'function', function: { name: 'mc_turn', description: '转动视角', parameters: { type: 'object', properties: { yaw: { type: 'number', description: '水平角度（0=正南, 正值=向西转, 负值=向东转）' }, pitch: { type: 'number', description: '垂直角度（正值=向上看, 负值=向下看, 范围-90到90）' } }, required: ['yaw', 'pitch'] } } },
            { type: 'function', function: { name: 'mc_use', description: '右键使用（开门、开箱子、吃食物、拉弓、与实体交互等）。duration>0 为持续使用模式（如吃食物约2秒、拉弓约1秒）', parameters: { type: 'object', properties: { duration: { type: 'number', description: '持续使用时长（秒）。不传或0为单次使用', default: 0 }, hand: { type: 'string', enum: ['main_hand', 'off_hand'], description: '使用哪只手', default: 'main_hand' } }, required: [] } } },
            { type: 'function', function: { name: 'mc_sneak', description: '潜行（蹲下）', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_unsneak', description: '取消潜行（站起来）', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_drop', description: '丢弃当前手持物品', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_goToBlock', description: '自动寻路到最近的指定方块。使用英文 ID（如 oak_log, stone, coal_ore）或中文名。状态里的 [minecraft:xxx] 就是 ID', parameters: { type: 'object', properties: { block_type: { type: 'string', description: '方块类型名称，如 oak_log, stone, coal_ore' }, range: { type: 'number', description: '搜索范围（格），默认 64', default: 64 } }, required: ['block_type'] } } },
            { type: 'function', function: { name: 'mc_goToPos', description: '自动寻路到指定坐标', parameters: { type: 'object', properties: { x: { type: 'number', description: 'X 坐标' }, y: { type: 'number', description: 'Y 坐标' }, z: { type: 'number', description: 'Z 坐标' } }, required: ['x', 'y', 'z'] } } },
            { type: 'function', function: { name: 'mc_stopNav', description: '停止当前寻路', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_goal', description: '设定一个 Minecraft 任务目标，AI 会自动反复执行直到完成。完成后用 mc_goalDone 结束', parameters: { type: 'object', properties: { task: { type: 'string', description: '任务描述，如"收集 10 个橡木原木"' } }, required: ['task'] } } },
            { type: 'function', function: { name: 'mc_goalDone', description: '标记当前任务已完成', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_digBlock', description: '持续挖掘视线前方的方块直到破坏，自动捡掉落物。比 mc_dig 更智能，会等到方块真正破坏', parameters: { type: 'object', properties: { timeout: { type: 'number', description: '超时时间（秒），默认 10', default: 10 } }, required: [] } } },
            { type: 'function', function: { name: 'mc_digDown', description: '安全向下挖掘，会自动检测岩浆和水', parameters: { type: 'object', properties: { distance: { type: 'integer', description: '向下挖几格，默认 1', default: 1 } }, required: [] } } },
            { type: 'function', function: { name: 'mc_goToSurface', description: '回到地面（向上挖）', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_attackEntity', description: '攻击最近的实体（怪物、动物等）', parameters: { type: 'object', properties: { type: { type: 'string', description: '实体类型，如 zombie, skeleton, cow。留空攻击任意' }, range: { type: 'number', description: '搜索范围，默认 16', default: 16 } }, required: [] } } },
            { type: 'function', function: { name: 'mc_equip', description: '装备物品（盔甲、盾牌等）', parameters: { type: 'object', properties: { item_name: { type: 'string', description: '物品名称，如 iron_chestplate, shield' } }, required: ['item_name'] } } },
            { type: 'function', function: { name: 'mc_consume', description: '吃食物或喝药水', parameters: { type: 'object', properties: { item_name: { type: 'string', description: '食物名称，如 bread, apple。留空自动吃任意食物' } }, required: [] } } },
            { type: 'function', function: { name: 'mc_craft', description: '合成物品。动态查询游戏内所有配方并自动尝试每一个，找到材料足够的配方进行合成，支持所有已注册的配方（原版+模组）。当一个物品有多种配方时（如木棍可用木板或竹子合成），系统会逐个尝试直到找到材料足够的配方。传入要合成的物品 ID（如 oak_planks, crafting_table, wooden_pickaxe, iron_ingot, shield, torch 等），系统会自动查找配方、检查材料（支持标签匹配，如任意木板均可）并合成。合成操作通过服务端同步完成，不会出现回档问题。建议先用 mc_queryRecipe 查询配方了解需要什么材料', parameters: { type: 'object', properties: { recipe: { type: 'string', description: '要合成的物品 ID，如 oak_planks, crafting_table, wooden_pickaxe, iron_pickaxe, shield, torch, bread' }, count: { type: 'integer', description: '合成数量，默认 1', default: 1 } }, required: ['recipe'] } } },
            { type: 'function', function: { name: 'mc_queryRecipe', description: '查询任意物品的合成配方。返回所需的材料、合成站（工作台/熔炉/背包等）和摆放方式。类似 JEI 物品管理器的配方查询功能。用于了解合成某个物品需要什么材料、在什么条件下合成、怎么摆放。支持原版和模组配方', parameters: { type: 'object', properties: { item: { type: 'string', description: '要查询的物品 ID，如 oak_planks, iron_ingot, diamond_pickaxe, shield' } }, required: ['item'] } } },
            { type: 'function', function: { name: 'mc_status', description: '查询 Minecraft 连接状态和当前游戏信息', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_enableAuto', description: '启用自动行为模式（自卫、防饥饿、防卡、拾取等底层自动行为）', parameters: { type: 'object', properties: {}, required: [] } } },
            { type: 'function', function: { name: 'mc_disableAuto', description: '禁用自动行为模式', parameters: { type: 'object', properties: {}, required: [] } } }
        ];
    }

    // ===== 执行 AI 调用的工具 =====
    async executeTool(name, params) {
        if (!this.mc || !this.mc.connected) {
            return 'Minecraft 未连接';
        }
        // AI 调用了工具，说明已响应
        this.aiThinking = false;
        this.noCommandCount = 0;

        switch (name) {
            case 'mc_look': return this.mc.stateToText(this.mc.getState());
            case 'mc_status': {
                const connected = this.mc && this.mc.connected;
                const state = this.mc.getState();
                let status = `连接状态: ${connected ? '已连接' : '未连接'}\n`;
                if (state) {
                    status += `位置: (${state.x?.toFixed(1)}, ${state.y?.toFixed(1)}, ${state.z?.toFixed(1)})\n`;
                    status += `生命值: ${state.health?.toFixed(1)}\n`;
                    if (state.food_level !== undefined) status += `饥饿值: ${state.food_level}\n`;
                }
                if (this.goal) status += `当前目标: ${this.goal}\n`;
                return status;
            }
            case 'mc_move': {
                const { direction, duration = 0.5 } = params;
                return this.sendActionAndWait({ action: 'move_forward', direction, duration });
            }
            case 'mc_dig': {
                const { duration = 2.0 } = params || {};
                return this.sendActionAndWait({ action: 'attack', duration });
            }
            case 'mc_place': {
                const { item_name = '' } = params || {};
                return this.sendActionAndWait({ action: 'place', item_name });
            }
            case 'mc_jump': return this.sendActionAndWait({ action: 'jump' });
            case 'mc_switch': {
                const { slot } = params;
                return this.sendActionAndWait({ action: 'switch_slot', slot });
            }
            case 'mc_turn': {
                const { yaw, pitch } = params;
                // AI 约定: 正值=向上看, 负值=向下看
                // Minecraft 约定: 正值=向下看, 负值=向上看
                // 发送给 mod 时需要反转
                return this.sendActionAndWait({ action: 'look_at', yaw, pitch: -pitch });
            }
            case 'mc_use': {
                const duration = params.duration || 0;
                const hand = params.hand || 'main_hand';
                return this.sendActionAndWait({ action: 'use', duration, hand });
            }
            case 'mc_sneak': return this.sendActionAndWait({ action: 'sneak' });
            case 'mc_unsneak': return this.sendActionAndWait({ action: 'unsneak' });
            case 'mc_drop': return this.sendActionAndWait({ action: 'drop' });
            case 'mc_goToBlock': {
                const { block_type, range = 64 } = params;
                return this.sendActionAndWait({ action: 'go_to_block', block_type, range });
            }
            case 'mc_goToPos': {
                const { x, y, z } = params;
                return this.sendActionAndWait({ action: 'go_to_pos', x, y, z });
            }
            case 'mc_stopNav': return this.sendActionAndWait({ action: 'stop_nav' });
            case 'mc_goal': {
                const { task } = params;
                this.setGoal(task);
                this.context.log('info', '新任务: ' + task);
                return '任务已设定: ' + task + '\n请立即开始执行第一步。';
            }
            case 'mc_goalDone': {
                const done = this.goal;
                this.stopGoal();
                this.context.log('info', '任务完成: ' + done);
                return '任务完成: ' + (done || '未知');
            }
            case 'mc_digBlock': {
                const { timeout = 10 } = params || {};
                return this.sendActionAndWait({ action: 'dig_block', timeout });
            }
            case 'mc_digDown': {
                const { distance = 1 } = params || {};
                return this.sendActionAndWait({ action: 'dig_down', distance });
            }
            case 'mc_goToSurface': return this.sendActionAndWait({ action: 'go_to_surface' });
            case 'mc_attackEntity': {
                const { type = '', range = 16 } = params || {};
                return this.sendActionAndWait({ action: 'attack_entity', type, range });
            }
            case 'mc_equip': {
                const { item_name } = params;
                return this.sendActionAndWait({ action: 'equip', item_name });
            }
            case 'mc_consume': {
                const { item_name = '' } = params || {};
                return this.sendActionAndWait({ action: 'consume', item_name });
            }
            case 'mc_enableAuto': return this.sendActionAndWait({ action: 'enable_auto' });
            case 'mc_disableAuto': return this.sendActionAndWait({ action: 'disable_auto' });
            case 'mc_craft': {
                const { recipe, count = 1 } = params;
                return this.sendActionAndWait({ action: 'craft', recipe, count });
            }
            case 'mc_queryRecipe': {
                const { item } = params;
                return this.sendActionAndWait({ action: 'query_recipe', item });
            }
            default: return '未知工具: ' + name;
        }
    }

    // ===== goalLoop 自主循环 =====

    setGoal(task) {
        this.goal = task;
        this.noCommandCount = 0;
        this.aiThinking = false;
        this.lastGoalTriggerTime = 0;
        if (this.goalLoop) clearInterval(this.goalLoop);
        // 6 秒间隔：给 AI 足够时间响应上一个动作，减少堆叠
        this.goalLoop = setInterval(() => this.runGoalLoop(), 6000);
        this.context.log('info', '自主循环已启动，间隔 6 秒');
        // 持续把游戏状态注入系统提示词
        this._refreshSystemPrompt();
    }

    runGoalLoop() {
        if (!this.goal || !this.mc || !this.mc.connected) return;
        // 有 pending 动作时不触发新循环
        if (this._pendingCalls.size > 0) return;
        if (this.aiThinking) return;

        // 最大空转限制：连续 10 次循环（约 60 秒）无工具调用时自动停止
        if (this.noCommandCount >= 10) {
            console.log('[MC-Control] AI 连续 60 秒未执行操作，自动停止目标循环');
            this.stopGoal();
            return;
        }

        const now = Date.now();
        // 距上次触发至少 6 秒，避免与上一轮 AI 响应重叠
        if (now - this.lastGoalTriggerTime < 5000) return;
        this.lastGoalTriggerTime = now;

        // 状态已通过系统提示词持续注入，这里只发简短催促，不重复全量状态
        let prompt;
        if (this.noCommandCount >= 3) {
            prompt = `【紧急】你已经 ${this.noCommandCount * 6} 秒没有执行任何操作了！你必须立即使用一个 mc_ 工具来继续任务。当前任务目标：${this.goal}`;
        } else {
            prompt = `请继续执行任务：${this.goal}。使用 mc_ 工具执行下一步操作。`;
        }

        // #5: SDK 真实方法 context.sendMessage，走完整 LLM 流程
        this.context.sendMessage(prompt).catch(e => {
            console.error('[MC-Control] 触发 AI 失败: ' + e.message);
        });
        this.aiThinking = true;
        this.noCommandCount++;
    }

    stopGoal() {
        this.goal = null;
        if (this.goalLoop) {
            clearInterval(this.goalLoop);
            this.goalLoop = null;
        }
        this.noCommandCount = 0;
        this.aiThinking = false;
        // 刷新系统提示词（移除目标行，保留游戏状态）
        this._refreshSystemPrompt();
    }

    /**
     * #14: 将最新游戏状态作为系统提示词片段持续注入。
     * addSystemPromptPatch 是幂等的（同 id 覆盖），不进对话历史，不触发 TTS。
     */
    _refreshSystemPrompt() {
        if (!this.context || !this.context.addSystemPromptPatch) return;
        const state = this.latestState;
        if (!state || !this.mc) {
            this.context.removeSystemPromptPatch('mc-state');
            return;
        }
        let patch = '\n--- Minecraft 实时状态 ---\n' + this.mc.stateToText(state);
        if (this.goal) patch += '\n当前任务目标: ' + this.goal;
        patch += '\n\n【重要规则】控制 Minecraft 时必须且只能使用 mc_ 开头的工具。'
              + '\n禁止使用 execute_code、type_text、mouse_click、press_key 等工具模拟键盘鼠标操作。'
              + '\n所有游戏交互（移动、挖掘、合成、攻击等）都通过 mc_ 工具完成。'
              + '\n\n【视角控制】mc_turn 的 pitch 参数：正值=向上看，负值=向下看（范围 -90 到 90）。'
              + '\n状态中显示的 pitch 也遵循此约定。'
              + '\n\n【合成系统】mc_craft 支持动态配方查询，可合成任何有配方的物品（不限于预设列表）。'
              + '\n当一个物品有多种配方时，系统会自动尝试每一个配方，找到材料足够的进行合成。'
              + '\n3×3 配方需要附近有工作台（5 格内），2×2 配方可在背包直接合成。'
              + '\n合成前建议先用 mc_queryRecipe 查询配方，了解需要什么材料、在什么条件下合成、怎么摆放。';
        patch += '\n--- End Minecraft ---';
        this.context.addSystemPromptPatch('mc-state', patch);
    }

    // ===== 动作执行结果反馈（#6: 多 pending 调用管理） =====

    async sendActionAndWait(action, customTimeout) {
        let timeout = customTimeout || 10000;
        if (action.action === 'go_to_pos' || action.action === 'go_to_block') {
            timeout = 35000;
        } else if (action.action === 'dig_down') {
            timeout = Math.max(10000, (action.distance || 1) * 3500);
        } else if (action.action === 'go_to_surface') {
            timeout = 30000;
        } else if (action.action === 'dig_block') {
            timeout = Math.max(10000, (action.timeout || 5) * 1000 + 5000);
        } else if (action.action === 'craft') {
            timeout = 15000;
        } else if (action.action === 'query_recipe') {
            timeout = 8000;
        }

        return new Promise((resolve) => {
            const callId = ++this._callCounter;
            this.noCommandCount = 0;

            const timeoutHandle = setTimeout(() => {
                // 超时：从 pending 移除并 resolve
                if (this._pendingCalls.has(callId)) {
                    this._pendingCalls.delete(callId);
                }
                resolve('⚠️ 动作执行超时，可能仍在进行中');
            }, timeout);

            this._pendingCalls.set(callId, {
                resolve,
                timer: timeoutHandle,
                action: action.action
            });

            this.mc.sendAction(action);
        });
    }

    /**
     * #6: 处理模组回传的 action_result，按 callId 匹配 pending 调用。
     * 由于模组侧 action_result 不带回传 callId，按 FIFO 顺序匹配最早的 pending 调用
     * （动作是顺序执行的，结果按顺序返回）。
     */
    _handleActionResult(result) {
        if (this._pendingCalls.size === 0) return;
        // 取最早的 pending 调用（FIFO）
        const firstKey = this._pendingCalls.keys().next().value;
        const pending = this._pendingCalls.get(firstKey);
        if (!pending) return;

        clearTimeout(pending.timer);
        this._pendingCalls.delete(firstKey);
        this.noCommandCount = 0;

        const success = result.success !== false;
        const msg = result.message || (success ? '成功' : '失败');
        pending.resolve((success ? '✅ ' : '❌ ') + msg);
    }
}

module.exports = MCControlPlugin;
