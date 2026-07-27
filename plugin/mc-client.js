const WebSocket = require('ws');

class MCClient {
    constructor(url) {
        this.url = url;
        this.ws = null;
        this.latestState = null;
        this.onState = null;
        this.connected = false;
    }

    connect() {
        return new Promise((resolve, reject) => {
            this.ws = new WebSocket(this.url);
            this.ws.on('open', () => {
                this.connected = true;
                resolve();
            });
            this.ws.on('message', (data) => {
                try {
                    this.latestState = JSON.parse(data.toString());
                    if (this.onState) this.onState(this.latestState);
                } catch (e) {}
            });
            this.ws.on('close', () => {
                this.connected = false;
            });
            this.ws.on('error', (err) => {
                reject(err);
            });
        });
    }

    sendAction(action) {
        if (this.ws && this.connected) {
            this.ws.send(JSON.stringify(action));
        }
    }

    getState() {
        return this.latestState;
    }

    stateToText(state) {
        if (!state) return '(未连接 Minecraft)';
        const lines = [];
        lines.push(`位置: (${state.x?.toFixed(1)}, ${state.y?.toFixed(1)}, ${state.z?.toFixed(1)})`);
        lines.push(`朝向: yaw=${state.yaw?.toFixed(1)}, pitch=${state.pitch?.toFixed(1)}`);
        lines.push(`生命: ${state.health?.toFixed(1)}`);
        lines.push(`世界: ${state.dimension || '未知'}`);

        if (state.looking_at_block && state.looking_at_block !== 'none') {
            const idHint = state.looking_at_block_id ? ` [${state.looking_at_block_id}]` : '';
            lines.push(`视线前方: ${state.looking_at_block}${idHint} (${state.looking_at_pos})`);
        }

        // 脚下信息
        if (state.block_below) {
            const idHint = state.block_below_id ? ` [${state.block_below_id}]` : '';
            const ground = state.on_ground ? '在地面' : '在空中';
            lines.push(`脚下: ${state.block_below}${idHint} (${ground})`);
        }

        if (state.inventory) {
            const items = state.inventory
                .filter(i => i.name !== 'empty')
                .map(i => `${i.name}×${i.count}`);
            if (items.length > 0) lines.push(`背包: ${items.join(', ')}`);
        }

        lines.push(`手持槽位: ${state.selected_slot}`);
        return lines.join('\n');
    }

    disconnect() {
        if (this.ws) this.ws.close();
    }
}

module.exports = MCClient;