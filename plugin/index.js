const { Plugin } = require('../../../js/core/plugin-base.js');
const MCClient = require('./mc-client.js');

class MCControlPlugin extends Plugin {
    async onStart() {
        const cfg = this.context.getPluginConfig();
        this.mc = new MCClient(cfg.ws_url || 'ws://127.0.0.1:8765');

        try {
            await this.mc.connect();
            this.context.log('info', '✅ 已连接 Minecraft 客户端');
        } catch (e) {
            this.context.log('error', '❌ 连接失败: ' + e.message);
            return;
        }

        this.mc.onState = (state) => {
            this.latestState = state;
        };

        this.goal = null;
        this.goalLoop = null;
    }

    async onStop() {
        this.stopGoal();
        if (this.mc) this.mc.disconnect();
    }

    // ===== 消息拦截：注入 MC 状态到上下文 =====
    async onUserInput(event) {
        if (!this.mc || !this.mc.connected) return;

        const stateText = this.mc.stateToText(this.mc.getState());
        const msg = event.getText() || '';

        const mcKeywords = ['mc', '我的世界', 'minecraft', '挖矿', '砍树', '建造', '合成', '背包', '方块', '挖', '矿'];
        const isMCRelated = mcKeywords.some(k => msg.toLowerCase().includes(k.toLowerCase()));

        if (isMCRelated) {
            let ctx = '\n【Minecraft 当前状态】\n' + stateText + '\n';
            if (this.goal) {
                ctx += '【当前任务目标】' + this.goal + '\n请继续执行任务，使用 mc_goToBlock 寻路，mc_dig 挖掘。每步只做一个动作。\n';
            }
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
                    description: '右键使用（开门、开箱子等）',
                    parameters: { type: 'object', properties: {}, required: [] }
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
            // === 新增：寻路 ===
            {
                type: 'function',
                function: {
                    name: 'mc_goToBlock',
                    description: '自动寻路到最近的指定方块（如 oak_log、stone），到达后停止',
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
            // === 新增：目标任务 ===
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
            }
        ];
    }

    // ===== 执行 AI 调用的工具 =====
    async executeTool(name, params) {
        if (!this.mc || !this.mc.connected) {
            return '❌ Minecraft 未连接';
        }

        switch (name) {
            case 'mc_look': {
                return this.mc.stateToText(this.mc.getState());
            }
            case 'mc_move': {
                const { direction, duration = 0.5 } = params;
                this.mc.sendAction({ action: 'move_forward', direction, duration });
                return '✅ 向' + direction + '移动 ' + duration + '秒';
            }
            case 'mc_dig': {
                const { duration = 2.0 } = params || {};
                this.mc.sendAction({ action: 'attack', duration });
                return '✅ 挖掘 ' + duration + '秒';
            }
            case 'mc_place': {
                this.mc.sendAction({ action: 'place' });
                return '✅ 放置方块';
            }
            case 'mc_jump': {
                this.mc.sendAction({ action: 'jump' });
                return '✅ 跳跃';
            }
            case 'mc_switch': {
                const { slot } = params;
                this.mc.sendAction({ action: 'switch_slot', slot });
                return '✅ 切换到槽位 ' + slot;
            }
            case 'mc_turn': {
                const { yaw, pitch } = params;
                this.mc.sendAction({ action: 'look_at', yaw, pitch });
                return '✅ 转向 yaw=' + yaw + ', pitch=' + pitch;
            }
            case 'mc_use': {
                this.mc.sendAction({ action: 'use' });
                return '✅ 使用';
            }
            case 'mc_sneak': {
                this.mc.sendAction({ action: 'sneak' });
                return '✅ 潜行';
            }
            case 'mc_unsneak': {
                this.mc.sendAction({ action: 'unsneak' });
                return '✅ 取消潜行';
            }
            case 'mc_drop': {
                this.mc.sendAction({ action: 'drop' });
                return '✅ 丢弃';
            }
            // === 寻路 ===
            case 'mc_goToBlock': {
                const { block_type, range = 64 } = params;
                this.mc.sendAction({ action: 'go_to_block', block_type, range });
                return '✅ 寻路到 ' + block_type + '（范围 ' + range + ' 格）...';
            }
            case 'mc_goToPos': {
                const { x, y, z } = params;
                this.mc.sendAction({ action: 'go_to_pos', x, y, z });
                return '✅ 寻路到 (' + x + ', ' + y + ', ' + z + ')...';
            }
            case 'mc_stopNav': {
                this.mc.sendAction({ action: 'stop_nav' });
                return '✅ 已停止寻路';
            }
            // === 目标任务 ===
            case 'mc_goal': {
                const { task } = params;
                this.goal = task;
                this.context.log('info', '🎯 新任务: ' + task);
                return '✅ 任务已设定: ' + task + '\n请立即开始执行第一步。每步只做一个动作，执行完等我告诉你状态更新。';
            }
            case 'mc_goalDone': {
                const done = this.goal;
                this.stopGoal();
                this.context.log('info', '✅ 任务完成: ' + done);
                return '✅ 任务完成: ' + (done || '未知');
            }
            default:
                return '❌ 未知工具: ' + name;
        }
    }

    stopGoal() {
        this.goal = null;
        if (this.goalLoop) {
            clearInterval(this.goalLoop);
            this.goalLoop = null;
        }
    }
}

module.exports = MCControlPlugin;