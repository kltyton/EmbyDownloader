const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

exports.install = function () {
    let child;
    let sequence = 0;
    const pending = new Map();
    const listeners = new Set();
    const allowed = new Set(['configure', 'search', 'list', 'enqueue', 'tasks', 'control', 'settings', 'clear']);

    function failAll(message) {
        pending.forEach(entry => entry.reject(new Error(message)));
        pending.clear();
        listeners.forEach(fn => fn({ event: 'error', error: message }));
    }

    function start() {
        if (child) return;
        const backend = path.join(__dirname, 'backend');
        const work = path.join(__dirname, 'work');
        const java = path.join(backend, 'runtime', 'bin', 'java.exe');
        if (!fs.existsSync(java)) throw new Error('下载插件后台未安装，请重新安装完整插件包');
        fs.mkdirSync(work, { recursive: true });
        const instance = spawn(java, ['-Djava.net.useSystemProxies=true', '-Djava.io.tmpdir=' + work,
            '-Dembydownload.subtitleCacheDir=' + path.join(__dirname, 'subtitle-cache'),
            '-m', 'com.dud.embydownload/com.dud.embydownload.LauncherKt', '--theater-service'], {
            cwd: backend, windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'],
            env: Object.assign({}, process.env, { TEMP: work, TMP: work, TMPDIR: work })
        });
        child = instance;
        const lines = readline.createInterface({ input: instance.stdout });
        lines.on('line', line => {
            let response;
            try { response = JSON.parse(line); }
            catch (_) { failAll('下载后台返回了无效响应'); return; }
            if (response.requestId) {
                const request = pending.get(response.requestId);
                if (!request) return;
                pending.delete(response.requestId);
                if (response.ok) request.resolve(response.result);
                else request.reject(new Error(response.error || '下载操作失败'));
            } else if (response.event) listeners.forEach(fn => fn(response));
        });
        instance.on('error', () => failAll('无法启动下载后台'));
        instance.on('exit', () => {
            lines.close();
            if (child === instance) child = null;
            failAll('下载后台已退出，重新打开下载页可重连');
        });
        instance.stdin.on('error', () => failAll('下载后台连接已关闭'));
    }

    window.EmbyDownloadBridge = Object.freeze({
        call(action, payload) {
            if (!allowed.has(action)) return Promise.reject(new Error('不支持此下载操作'));
            return new Promise((resolve, reject) => {
                try {
                    start();
                    const requestId = String(++sequence);
                    const body = JSON.stringify(Object.assign({}, payload, { action, requestId }));
                    if (Buffer.byteLength(body, 'utf8') > 1048576) throw new Error('选择的任务过多');
                    pending.set(requestId, { resolve, reject });
                    child.stdin.write(body + '\n');
                } catch (error) { reject(error); }
            });
        },
        subscribe(listener) {
            listeners.add(listener);
            return () => listeners.delete(listener);
        }
    });
    window.addEventListener('beforeunload', () => {
        if (child) child.stdin.end();
    });
};
