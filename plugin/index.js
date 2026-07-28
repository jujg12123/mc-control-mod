const { Plugin } = require('../../../js/core/plugin-base.js');
const MCClient = require('./mc-client.js');

class MCControlPlugin extends Plugin {
    async onStart() {
        const cfg = this.context.getPluginConfig();
        this.mc = new MCClient(cfg.ws_url || 'ws://127.0.0.1:8765');

        // 初始化字段
        this.goal = null;
        this.goalLoop = null;
        this.noCommandCount = 0;
        this.lastActionPending = false;
        this.latestState = null;
        this.aiThinking = false; // AI 是否正在思考（防止 prompt 堆叠）
        this._callCounter = 0; // 唯一调用 ID 计数器
        this._pendingCallId = null; // 当前待处理调用的 ID（用于结果校验）

        // 设置回调
        this.mc.onState = (state) => {
            this.latestState = state;
        };
        this.mc.onActionResult = null;

        // 尝试连接（失败时自动重连）
        try {
            await this.mc.connect();
            this.context.log('info', '✅ 已连接 Minecraft 客户端');
        } catch (e) {
            this.context.log('error', '❌ 初始连接失败: ' + e.message + '，将自动重连...');
        }

        // P4: auto_control 配置生效
        const autoControl = cfg.auto_control !== undefined ? cfg.auto_control : false;
        if (autoControl) {
            setTimeout(() => {
                this.setGoal('在 Minecraft 中生存和发展：收集资源、建造庇护所、探索世界');
            }, 3000);
        }
    }

    async onStop() {
        this.stopGoal();
        if (this.mc) this.mc.disconnect();
    }

    // ===== 消息拦截：注入 MC 状态到上下文 =====
    async onUserInput(event) {
        this.aiThinking = false; // 用户有新输入，重置思考标志
        if (!this.mc || !this.mc.connected) return;

        const msg = event.text || '';

        // P6: 有活跃目标时，始终注入游戏状态
        if (this.goal) {
            const state = this.mc.getState();
            if (state) {
                const stateText = this.mc.stateToText(state);
                let ctx = '\n【Minecraft 当前状态】\n' + stateText + '\n';
                ctx += '【当前任务目标】' + this.goal + '\n请继续执行任务...\n';
                event.addContext(ctx);
            }
            return;
        }

        // P6: 无目标时，仅在 MC 相关关键词时注入
        const mcKeywords = ['mc', '我的世界', 'minecraft', '挖矿', '砍树', '建造', '合成', '背包', '方块', '挖', '矿'];
        const isMCRelated = mcKeywords.some(k => msg.toLowerCase().includes(k.toLowerCase()));

        if (isMCRelated) {
            const stateText = this.mc.stateToText(this.mc.getState());
            let ctx = '\n【Minecraft 当前状态】\n' + stateText + '\n';
            event.addContext(ctx);
        }
    }

    // ===== 注册 AI 工具 =====
    getTools() {
        return [
            {
                type: 'function',
                function: {
                    name: 'mc_look',
                    description: '查看 Minecraft 当前状态：位置、视线、背包、脚下',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_move',
                    description: '控制角色移动',
                    parameters: {
                        type: 'object',
                        properties: {
                            direction: { type: 'string', enum: ['forward', 'back', 'left', 'right'], description: '移动方向' },
                            duration: { type: 'number', description: '移动时长（秒），默认 0.5', default: 0.5 }
                        },
                        required: ['direction']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_dig',
                    description: '挖掘/攻击视线前方的方块，默认持续 2 秒',
                    parameters: {
                        type: 'object',
                        properties: {
                            duration: { type: 'number', description: '挖掘时长（秒），木头约 2s，石头约 3-5s', default: 2.0 }
                        },
                        required: []
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_place',
                    description: '在视线前方放置方块',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_jump',
                    description: '跳跃',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_switch',
                    description: '切换快捷栏物品',
                    parameters: {
                        type: 'object',
                        properties: { slot: { type: 'integer', description: '槽位号 (0-8)' } },
                        required: ['slot']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_turn',
                    description: '转动视角',
                    parameters: {
                        type: 'object',
                        properties: {
                            yaw: { type: 'number', description: '水平角度' },
                            pitch: { type: 'number', description: '垂直角度' }
                        },
                        required: ['yaw', 'pitch']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_use',
                    description: '右键使用（开门、开箱子、吃食物、拉弓、与实体交互等）。duration>0 为持续使用模式（如吃食物约2秒、拉弓约1秒）',
                    parameters: {
                        type: 'object',
                        properties: {
                            duration: { type: 'number', description: '持续使用时长（秒）。不传或0为单次使用', default: 0 },
                            hand: { type: 'string', enum: ['main_hand', 'off_hand'], description: '使用哪只手', default: 'main_hand' }
                        },
                        required: []
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_sneak',
                    description: '潜行（蹲下）',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_unsneak',
                    description: '取消潜行（站起来）',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_drop',
                    description: '丢弃当前手持物品',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            // === 寻路 ===
            {
                type: 'function',
                function: {
                    name: 'mc_goToBlock',
                    description: '自动寻路到最近的指定方块。使用英文 ID（如 oak_log, stone, coal_ore）或中文名（如 橡木原木, 石头）。状态里的 [minecraft:xxx] 就是 ID',
                    parameters: {
                        type: 'object',
                        properties: {
                            block_type: { type: 'string', description: '方块类型名称，如 oak_log, stone, coal_ore' },
                            range: { type: 'number', description: '搜索范围（格），默认 64', default: 64 }
                        },
                        required: ['block_type']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_goToPos',
                    description: '自动寻路到指定坐标',
                    parameters: {
                        type: 'object',
                        properties: {
                            x: { type: 'number', description: 'X 坐标' },
                            y: { type: 'number', description: 'Y 坐标' },
                            z: { type: 'number', description: 'Z 坐标' }
                        },
                        required: ['x', 'y', 'z']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_stopNav',
                    description: '停止当前寻路',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            // === 目标任务 ===
            {
                type: 'function',
                function: {
                    name: 'mc_goal',
                    description: '设定一个 Minecraft 任务目标，AI 会自动反复执行直到完成。完成后用 mc_goalDone 结束',
                    parameters: {
                        type: 'object',
                        properties: {
                            task: { type: 'string', description: '任务描述，如"收集 10 个橡木原木"' }
                        },
                        required: ['task']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_goalDone',
                    description: '标记当前任务已完成',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            // === 更多动作 ===
            {
                type: 'function',
                function: {
                    name: 'mc_digBlock',
                    description: '持续挖掘视线前方的方块直到破坏，自动捡掉落物。比 mc_dig 更智能，会等到方块真正破坏',
                    parameters: {
                        type: 'object',
                        properties: {
                            timeout: { type: 'number', description: '超时时间（秒），默认 10', default: 10 }
                        },
                        required: []
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_digDown',
                    description: '安全向下挖掘，会自动检测岩浆和水',
                    parameters: {
                        type: 'object',
                        properties: {
                            distance: { type: 'integer', description: '向下挖几格，默认 1', default: 1 }
                        },
                        required: []
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_goToSurface',
                    description: '回到地面（向上跳跃垫脚）',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_attackEntity',
                    description: '攻击最近的实体（怪物、动物等）',
                    parameters: {
                        type: 'object',
                        properties: {
                            type: { type: 'string', description: '实体类型，如 zombie, skeleton, cow。留空攻击任意' },
                            range: { type: 'number', description: '搜索范围，默认 16', default: 16 }
                        },
                        required: []
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_equip',
                    description: '装备物品（盔甲、盾牌等）',
                    parameters: {
                        type: 'object',
                        properties: {
                            item_name: { type: 'string', description: '物品名称，如 iron_chestplate, shield' }
                        },
                        required: ['item_name']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_consume',
                    description: '吃食物或喝药水',
                    parameters: {
                        type: 'object',
                        properties: {
                            item_name: { type: 'string', description: '食物名称，如 bread, apple。留空自动吃任意食物' }
                        },
                        required: []
                    }
                }
            },
            // === 新增：状态查询 ===
            {
                type: 'function',
                function: {
                    name: 'mc_status',
                    description: '查询 Minecraft 连接状态和当前游戏信息',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            // === 自动行为管理 ===
            {
                type: 'function',
                function: {
                    name: 'mc_enableAuto',
                    description: '启用自动行为模式（自卫、防饥饿、防卡、拾取等底层自动行为）',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_disableAuto',
                    description: '禁用自动行为模式',
                    parameters: { type: 'object', properties: {}, required: [] }
                }
            }
        ];
    }

    // ===== 执行 AI 调用的工具 =====
    async executeTool(name, params) {
        if (!this.mc || !this.mc.connected) {
            return '❌ Minecraft 未连接';
        }

        // AI 调用了工具，说明已响应，重置思考标志和空转计数 (P1)
        this.aiThinking = false;
        this.noCommandCount = 0;

        switch (name) {
            case 'mc_look': {
                return this.mc.stateToText(this.mc.getState());
            }
            // P7: mc_status 工具
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
                return this.sendActionAndWait({ action: 'place' });
            }
            case 'mc_jump': {
                return this.sendActionAndWait({ action: 'jump' });
            }
            case 'mc_switch': {
                const { slot } = params;
                return this.sendActionAndWait({ action: 'switch_slot', slot });
            }
            case 'mc_turn': {
                const { yaw, pitch } = params;
                return this.sendActionAndWait({ action: 'look_at', yaw, pitch });
            }
            case 'mc_use': {
                const duration = params.duration || 0;
                const hand = params.hand || 'main_hand';
                return this.sendActionAndWait({ action: 'use', duration, hand });
            }
            case 'mc_sneak': {
                return this.sendActionAndWait({ action: 'sneak' });
            }
            case 'mc_unsneak': {
                return this.sendActionAndWait({ action: 'unsneak' });
            }
            case 'mc_drop': {
                return this.sendActionAndWait({ action: 'drop' });
            }
            // === 寻路 ===
            case 'mc_goToBlock': {
                const { block_type, range = 64 } = params;
                return this.sendActionAndWait({ action: 'go_to_block', block_type, range });
            }
            case 'mc_goToPos': {
                const { x, y, z } = params;
                return this.sendActionAndWait({ action: 'go_to_pos', x, y, z });
            }
            case 'mc_stopNav': {
                return this.sendActionAndWait({ action: 'stop_nav' });
            }
            // === 目标任务 ===
            case 'mc_goal': {
                const { task } = params;
                this.setGoal(task);
                this.context.log('info', '🎯 新任务: ' + task);
                return '✅ 任务已设定: ' + task + '\n请立即开始执行第一步。';
            }
            case 'mc_goalDone': {
                const done = this.goal;
                this.stopGoal();
                this.context.log('info', '✅ 任务完成: ' + done);
                return '✅ 任务完成: ' + (done || '未知');
            }
            // === 更多动作 ===
            case 'mc_digBlock': {
                const { timeout = 10 } = params || {};
                return this.sendActionAndWait({ action: 'dig_block', timeout });
            }
            case 'mc_digDown': {
                const { distance = 1 } = params || {};
                return this.sendActionAndWait({ action: 'dig_down', distance });
            }
            case 'mc_goToSurface': {
                return this.sendActionAndWait({ action: 'go_to_surface' });
            }
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
            // === 自动行为管理 ===
            case 'mc_enableAuto': return this.sendActionAndWait({ action: 'enable_auto' });
            case 'mc_disableAuto': return this.sendActionAndWait({ action: 'disable_auto' });
            default:
                return '❌ 未知工具: ' + name;
        }
    }

    // ===== P1: goalLoop 自主循环 =====

    setGoal(task) {
        this.goal = task;
        this.noCommandCount = 0;
        this.lastActionPending = false;
        this.aiThinking = false;
        if (this.goalLoop) clearInterval(this.goalLoop);
        this.goalLoop = setInterval(() => this.runGoalLoop(), 4000);
        this.context.log('info', '🔄 自主循环已启动，间隔 4 秒');
    }

    runGoalLoop() {
        if (!this.goal || !this.mc || !this.mc.connected) return;
        // cooldown：上一个动作结果未返回时不触发新循环
        if (this.lastActionPending) return;
        if (this.aiThinking) return; // AI 正在思考，不触发新 prompt

        // 最大空转限制：连续 10 次循环（约 40 秒）无工具调用时自动停止
        if (this.noCommandCount >= 10) {
            console.log('[MC-Control] AI 连续 40 秒未执行操作，自动停止目标循环');
            this.stopGoal();
            return;
        }

        const state = this.mc.getState();
        if (!state) return;

        const stateText = this.mc.stateToText(state);

        let prompt;
        if (this.noCommandCount >= 3) {
            // 防空转：连续 3 次循环无工具调用时注入更强提示
            prompt = `【紧急】你已经 ${this.noCommandCount * 4} 秒没有执行任何操作了！你必须立即使用一个 mc_ 工具来继续任务。\n当前任务目标：${this.goal}\n${stateText}`;
        } else {
            prompt = `请继续执行任务：${this.goal}\n${stateText}\n请使用 mc_ 工具执行下一步操作。`;
        }

        this.triggerAI(prompt);
        this.aiThinking = true; // 标记等待响应
        this.noCommandCount++;
    }

    stopGoal() {
        this.goal = null;
        if (this.goalLoop) {
            clearInterval(this.goalLoop);
            this.goalLoop = null;
        }
        this.noCommandCount = 0;
        this.lastActionPending = false;
        this.aiThinking = false;
    }

    // 程序化触发 AI 对话
    // 注意：My Neuro 插件 SDK 的 context 对象上可能提供不同名称的方法来触发 AI。
    // 此处按常见命名依次尝试，兼容不同 SDK 版本。
    triggerAI(prompt) {
        const ctx = this.context;
        if (!ctx) {
            console.error('[MC-Control] 无法触发 AI：context 不可用，停止目标循环');
            this.stopGoal();
            return;
        }

        if (typeof ctx.sendMessage === 'function') {
            ctx.sendMessage(prompt);
        } else if (typeof ctx.triggerInput === 'function') {
            ctx.triggerInput(prompt);
        } else if (typeof ctx.injectMessage === 'function') {
            ctx.injectMessage(prompt);
        } else if (typeof ctx.addUserMessage === 'function') {
            ctx.addUserMessage(prompt);
        } else if (typeof ctx.prompt === 'function') {
            ctx.prompt(prompt);
        } else if (typeof ctx.sendUserMessage === 'function') {
            ctx.sendUserMessage(prompt);
        } else if (typeof ctx.chat === 'function') {
            ctx.chat(prompt);
        } else if (typeof ctx.input === 'function') {
            ctx.input(prompt);
        } else {
            console.error('[MC-Control] 无法触发 AI 对话：未找到可用的 SDK 方法，停止目标循环');
            this.stopGoal();
        }
    }

    // ===== P2: 动作执行结果反馈 =====

    // 发送动作并等待 action_result 返回（根据动作类型动态超时）
    async sendActionAndWait(action, customTimeout) {
        let timeout = customTimeout || 10000;

        // 根据动作类型自动调整超时
        if (action.action === 'go_to_pos' || action.action === 'go_to_block') {
            timeout = 35000; // 寻路最长 30s + 余量
        } else if (action.action === 'dig_down') {
            timeout = Math.max(10000, (action.distance || 1) * 3500);
        } else if (action.action === 'go_to_surface') {
            timeout = 30000;
        } else if (action.action === 'dig_block') {
            timeout = Math.max(10000, (action.timeout || 5) * 1000 + 5000);
        }

        return new Promise((resolve) => {
            const callId = ++this._callCounter;
            this._pendingCallId = callId;
            this.lastActionPending = true;
            this.noCommandCount = 0;

            const timeoutHandle = setTimeout(() => {
                if (this._pendingCallId === callId) {
                    this.lastActionPending = false;
                    this._pendingCallId = null;
                }
                resolve('⚠️ 动作执行超时，可能仍在进行中');
            }, timeout);

            this.mc.onActionResult = (result) => {
                if (this._pendingCallId !== callId) return; // 忽略过期结果
                clearTimeout(timeoutHandle);
                this._pendingCallId = null;
                this.lastActionPending = false;
                this.noCommandCount = 0;
                const success = result.success !== false;
                const msg = result.message || (success ? '成功' : '失败');
                resolve((success ? '✅ ' : '❌ ') + msg);
            };

            this.mc.sendAction(action);
        });
    }
}

module.exports = MCControlPlugin;
