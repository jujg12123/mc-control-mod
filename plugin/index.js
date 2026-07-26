const { Plugin } = require('../../../js/core/plugin-base.js');
const MCClient = require('./mc-client.js');

class MCControlPlugin extends Plugin {
    async onStart() {
        const cfg = this.context.getPluginConfig();
        this.mc = new MCClient(cfg.ws_url || 'ws://localhost:8765');

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
    }

    async onStop() {
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
            event.addContext('\n【Minecraft 当前状态】\n' + stateText + '\n');
        }
    }

    // ===== 注册 AI 工具 =====
    getTools() {
        return [
            {
                type: 'function',
                function: {
                    name: 'mc_look',
                    description: '查看 Minecraft 当前状态：位置、视线、背包',
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
                            direction: {
                                type: 'string',
                                enum: ['forward', 'back', 'left', 'right'],
                                description: '移动方向'
                            },
                            duration: {
                                type: 'number',
                                description: '移动时长（秒），默认 0.5',
                                default: 0.5
                            }
                        },
                        required: ['direction']
                    }
                }
            },
            {
                type: 'function',
                function: {
                    name: 'mc_dig',
                    description: '挖掘/攻击视线前方的方块',
                    parameters: { type: 'object', properties: {}, required: [] }
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
                        properties: {
                            slot: { type: 'integer', description: '槽位号 (0-8)' }
                        },
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
                const state = this.mc.getState();
                return this.mc.stateToText(state);
            }

            case 'mc_move': {
                const { direction, duration = 0.5 } = params;
                this.mc.sendAction({ action: 'move_forward', direction, duration });
                return '✅ 向' + direction + '移动 ' + duration + '秒';
            }

            case 'mc_dig': {
                this.mc.sendAction({ action: 'attack' });
                return '✅ 挖掘/攻击';
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

            default:
                return '❌ 未知工具: ' + name;
        }
    }
}

module.exports = MCControlPlugin;