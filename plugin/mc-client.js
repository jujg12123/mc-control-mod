const WebSocket = require('ws');

class MCClient {
    constructor(url) {
        this.url = url;
        this.ws = null;
        this.latestState = null;
        this.onState = null;
        this.onActionResult = null;
        this.connected = false;
        // 自动重连 (P5)
        this.reconnectAttempts = 0;
        this.maxReconnectDelay = 30000;
        this.reconnectTimer = null;
        this.shouldReconnect = true;
    }

    connect() {
        return new Promise((resolve, reject) => {
            this.shouldReconnect = true;
            let ws;
            try {
                ws = new WebSocket(this.url);
            } catch (e) {
                reject(e);
                return;
            }
            this.ws = ws;

            ws.on('open', () => {
                this.connected = true;
                this.reconnectAttempts = 0;
                resolve();
            });

            // P2: 区分 state 和 action_result 消息
            ws.on('message', (data) => {
                try {
                    const msg = JSON.parse(data.toString());
                    if (msg.type === 'action_result') {
                        // 动作执行结果
                        if (this.onActionResult) this.onActionResult(msg);
                    } else if (msg.type === 'state') {
                        // 游戏状态更新
                        this.latestState = msg;
                        if (this.onState) this.onState(this.latestState);
                    } else {
                        // 兼容旧格式：无 type 字段的消息当作 state
                        this.latestState = msg;
                        if (this.onState) this.onState(this.latestState);
                    }
                } catch (e) {}
            });

            // P5: 断线自动重连
            ws.on('close', () => {
                this.connected = false;
                if (this.onState) this.onState(null);
                if (this.shouldReconnect) {
                    this.scheduleReconnect();
                }
            });

            ws.on('error', (err) => {
                // 仅在初始连接阶段 reject，连接建立后的错误由 onclose 处理
                if (!this.connected) {
                    reject(err);
                }
            });
        });
    }

    // P5: 指数退避重连
    scheduleReconnect() {
        if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), this.maxReconnectDelay);
        this.reconnectAttempts++;
        console.log(`[MC-Client] 将在 ${delay}ms 后重连... (第 ${this.reconnectAttempts} 次)`);
        this.reconnectTimer = setTimeout(() => {
            this.connect().catch(() => {
                // 连接失败，onclose 会触发下一次重连
            });
        }, delay);
    }

    sendAction(action) {
        if (this.ws && this.connected) {
            this.ws.send(JSON.stringify(action));
        }
    }

    getState() {
        return this.latestState;
    }

    // P3 + P6: 增强状态文本，加入附近实体和新状态字段
    stateToText(state) {
        if (!state) return '(未连接 Minecraft)';
        const lines = [];
        lines.push(`位置: (${state.x?.toFixed(1)}, ${state.y?.toFixed(1)}, ${state.z?.toFixed(1)})`);
        lines.push(`朝向: yaw=${state.yaw?.toFixed(1)}, pitch=${state.pitch?.toFixed(1)}`);
        lines.push(`生命: ${state.health?.toFixed(1)}`);

        // P6: 饥饿值
        if (state.food_level !== undefined) {
            let foodLine = `饥饿值: ${state.food_level}/20`;
            if (state.saturation !== undefined) foodLine += ` (饱和度: ${state.saturation.toFixed(1)})`;
            lines.push(foodLine);
        }

        // P6: 经验等级
        if (state.experience_level !== undefined) {
            lines.push(`经验等级: ${state.experience_level}`);
        }

        // P6: 游戏模式
        if (state.game_mode) {
            lines.push(`游戏模式: ${state.game_mode}`);
        }

        lines.push(`世界: ${state.dimension || '未知'}`);

        // P6: 生物群系
        if (state.biome) {
            lines.push(`生物群系: ${state.biome}`);
        }

        // P6: 时间和天气
        if (state.is_day !== undefined) {
            let timeLine = `时间: ${state.is_day ? '白天' : '夜晚'}`;
            if (state.weather && state.weather !== 'clear') {
                timeLine += ` (${state.weather === 'thunder' ? '雷暴' : '下雨'})`;
            }
            lines.push(timeLine);
        }

        if (state.looking_at_block && state.looking_at_block !== 'none') {
            const idHint = state.looking_at_block_id ? ` [${state.looking_at_block_id}]` : '';
            lines.push(`视线前方: ${state.looking_at_block}${idHint} (${state.looking_at_pos})`);

            // 周围 5×5×5 方块信息（以视线前方方块为中心）
            if (state.surrounding_blocks && state.surrounding_blocks.length > 0) {
                const total = 125; // 5×5×5
                const air = state.surrounding_air || 0;
                const nonAir = total - air;
                const blocks = state.surrounding_blocks.map(b => `${b.name}×${b.count}`);
                if (air > nonAir * 2) {
                    // 空气占多数，说明前方较空旷
                    blocks.push(`空气×${air}`);
                    lines.push(`周围5格: ${blocks.join(', ')} (前方较空旷)`);
                } else if (air > 0) {
                    blocks.push(`空气×${air}`);
                    lines.push(`周围5格: ${blocks.join(', ')}`);
                } else {
                    lines.push(`周围5格: ${blocks.join(', ')} (密集区域)`);
                }
            }
        }

        // 脚下信息
        if (state.block_below) {
            const idHint = state.block_below_id ? ` [${state.block_below_id}]` : '';
            const ground = state.on_ground ? '在地面' : '在空中';
            lines.push(`脚下: ${state.block_below}${idHint} (${ground})`);
        }

        // 头顶信息（让 AI 感知头顶是否被挡住）
        if (state.block_above && state.block_above !== 'air') {
            const idHint = state.block_above_id ? ` [${state.block_above_id}]` : '';
            lines.push(`头顶: ${state.block_above}${idHint} (被挡住, 跳跃无效)`);
        }

        if (state.inventory) {
            const items = state.inventory
                .filter(i => i.name !== 'empty')
                .map(i => `${i.name}×${i.count}`);
            if (items.length > 0) lines.push(`背包: ${items.join(', ')}`);
        }

        lines.push(`手持槽位: ${state.selected_slot}`);

        // P3: 附近实体
        if (state.nearby_entities && state.nearby_entities.length > 0) {
            lines.push('');
            lines.push('【附近实体】');
            state.nearby_entities.slice(0, 10).forEach(e => {
                const name = e.name || e.type || '未知';
                const dist = e.distance !== undefined ? e.distance.toFixed(1) : '?';
                lines.push(`- ${name} (距离 ${dist}格)`);
            });
        } else {
            lines.push('');
            lines.push('【附近实体】无');
        }

        // P6: 自动行为日志
        if (state.behavior_log && state.behavior_log.length > 0) {
            lines.push('');
            lines.push('【自动行为日志】');
            state.behavior_log.forEach(log => {
                lines.push(`- ${log.event}`);
            });
        }

        return lines.join('\n');
    }

    // P5: 主动断开，不触发重连
    disconnect() {
        this.shouldReconnect = false;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        this.connected = false;
    }
}

module.exports = MCClient;
