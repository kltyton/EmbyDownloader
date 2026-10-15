define(['baseView', 'connectionManager', 'loading', 'emby-button', 'emby-input', 'emby-select', 'emby-checkbox', 'emby-scroller'], function (BaseView, connectionManager, loading) {
    'use strict';
    var labels = { QUEUED: '排队', RUNNING: '传输中', PAUSED: '已暂停', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' };
    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }
    function size(bytes) {
        if (!(bytes >= 0)) return '未知大小';
        var units = ['B', 'KB', 'MB', 'GB'];
        var index = 0;
        while (bytes >= 1024 && index < 3) { bytes /= 1024; index++; }
        return bytes.toFixed(index ? 2 : 0) + ' ' + units[index];
    }
    function DownloadView(view, params) {
        BaseView.apply(this, arguments);
        this.params = params;
        this.rows = new Map();
        var self = this;
        view.querySelector('.downloadSearch').addEventListener('submit', function (event) {
            event.preventDefault();
            self.perform('.downloadSearchButton', function () {
                return self.call('search', { term: view.querySelector('.downloadTerm').value, type: view.querySelector('.downloadType').value }).then(function (items) {
                    var results = view.querySelector('.downloadResults');
                    results.textContent = '';
                    if (!items.length) results.appendChild(element('p', 'fieldDescription', '没有找到媒体'));
                    items.forEach(function (item) {
                        var button = element('button', 'emby-button raised', item.title || item.Name);
                        button.type = 'button';
                        button.addEventListener('click', function () { self.loadItem(item.id || item.Id); });
                        results.appendChild(button);
                    });
                });
            });
        });
        ['All', 'None'].forEach(function (suffix) {
            view.querySelector('.downloadSelect' + suffix).addEventListener('click', function () {
                view.querySelectorAll('.downloadItemChoice').forEach(function (input) { input.checked = suffix === 'All'; });
            });
        });
        view.querySelector('.downloadSaveSettings').addEventListener('click', function () {
            self.perform('.downloadSaveSettings', function () { return self.saveSettings().then(function () { self.message('设置已保存'); }); });
        });
        view.querySelector('.downloadEnqueue').addEventListener('click', function () {
            self.perform('.downloadEnqueue', function () {
                var ids = Array.from(view.querySelectorAll('.downloadItemChoice:checked')).map(function (input) { return input.value; });
                if (!ids.length) throw new Error('请先选择媒体');
                return self.saveSettings().then(function () {
                    return self.call('enqueue', { itemIds: ids, media: view.querySelector('.downloadMedia').checked,
                        subtitles: view.querySelector('.downloadSubtitles').checked, artwork: view.querySelector('.downloadArtwork').checked,
                        mode: view.querySelector('.downloadMode').value, directory: view.querySelector('.downloadDirectory').value,
                        localMediaPath: view.querySelector('.downloadLocalMedia').value });
                }).then(function (result) {
                    self.message('已加入 ' + result.added + ' 项任务' + (result.skipped && result.skipped.length ? '；' + result.skipped.join('；') : ''));
                    return self.refreshTasks();
                });
            });
        });
        view.querySelector('.downloadClear').addEventListener('click', function () {
            self.perform('.downloadClear', function () { return self.call('clear').then(function () { return self.refreshTasks(); }); });
        });
    }
    Object.assign(DownloadView.prototype, BaseView.prototype);
    DownloadView.prototype.call = function (action, payload) {
        if (!window.EmbyDownloadBridge) return Promise.reject(new Error('插件后台连接未加载，请重启 Emby Theater'));
        return window.EmbyDownloadBridge.call(action, payload);
    };
    DownloadView.prototype.message = function (text) { this.view.querySelector('.downloadMessage').textContent = text; };
    DownloadView.prototype.perform = function (selector, work) {
        var self = this;
        var button = this.view.querySelector(selector);
        button.disabled = true;
        this.message('正在处理…');
        Promise.resolve().then(work).catch(function (error) { self.message(error.message); }).finally(function () { button.disabled = false; });
    };
    DownloadView.prototype.saveSettings = function () {
        return this.call('settings', { directory: this.view.querySelector('.downloadDirectory').value,
            mode: this.view.querySelector('.downloadMode').value, concurrent: Number(this.view.querySelector('.downloadConcurrent').value) });
    };
    DownloadView.prototype.loadItem = function (id) {
        var self = this;
        this.message('正在加载媒体与字幕…');
        return this.call('list', { itemId: id }).then(function (items) {
            var container = self.view.querySelector('.downloadSelection');
            container.textContent = '';
            items.forEach(function (item) {
                var row = element('div', 'checkboxContainer');
                var label = element('label');
                var input = document.createElement('input', { is: 'emby-checkbox' });
                input.setAttribute('is', 'emby-checkbox');
                input.type = 'checkbox'; input.className = 'downloadItemChoice'; input.value = item.id; input.checked = true;
                label.appendChild(input);
                label.appendChild(element('span', '', item.title));
                row.appendChild(label);
                var subtitles = item.subtitles || [];
                row.appendChild(element('p', 'fieldDescription', subtitles.length ? '字幕：' + subtitles.map(function (sub) { return sub.label + ' (' + sub.format.toUpperCase() + (sub.requiresMedia ? '，从播放流提取' : '') + ')'; }).join('、') : '无字幕轨道'));
                container.appendChild(row);
            });
            self.message('已加载 ' + items.length + ' 个媒体条目');
        }).catch(function (error) { self.message(error.message); });
    };
    DownloadView.prototype.refreshTasks = function () {
        var self = this;
        return this.call('tasks').then(function (tasks) { self.renderTasks(tasks); });
    };
    DownloadView.prototype.renderTasks = function (tasks) {
        var self = this;
        var container = this.view.querySelector('.downloadTasks');
        if (!this.rows.size) container.textContent = '';
        var remaining = new Set();
        tasks.forEach(function (task) {
            remaining.add(task.id);
            var row = self.rows.get(task.id);
            if (!row) {
                row = element('div', 'downloadTask');
                row.titleNode = element('h3'); row.statusNode = element('p', 'fieldDescription');
                row.progressNode = element('progress'); row.progressNode.max = 1;
                row.fileNode = element('p', 'fieldDescription'); row.fileNode.style.overflowWrap = 'anywhere';
                row.actionsNode = element('div', 'flex flex-wrap-wrap');
                ['pause', 'resume', 'retry', 'cancel'].forEach(function (operation) {
                    var text = { pause: '暂停', resume: '继续', retry: '重试', cancel: '取消' }[operation];
                    var button = element('button', 'emby-button raised', text); button.type = 'button'; button.dataset.operation = operation;
                    button.addEventListener('click', function () {
                        button.disabled = true;
                        self.call('control', { id: task.id, operation: operation }).then(function () { return self.refreshTasks(); }).catch(function (error) { self.message(error.message); button.disabled = false; });
                    });
                    row.actionsNode.appendChild(button);
                });
                [row.titleNode, row.statusNode, row.progressNode, row.fileNode, row.actionsNode].forEach(function (node) { row.appendChild(node); });
                container.appendChild(row); self.rows.set(task.id, row);
            }
            row.titleNode.textContent = task.title + ' · ' + ({ MEDIA: '媒体', SUBTITLE: '字幕', ARTWORK: '封面' }[task.kind] || task.kind);
            row.statusNode.textContent = (labels[task.state] || task.state) + ' · ' + size(task.downloaded) + ' / ' + size(task.total) + ' · ' + size(task.speed || 0) + '/s' + (task.phase === 'EXTRACTING' ? ' · 从播放流提取字幕，已读取 ' + size(task.sourceBytes) + ' / ' + size(task.sourceTotal) : '') + (task.error ? ' · ' + task.error : '');
            if (task.total > 0) row.progressNode.value = Math.min(1, task.downloaded / task.total);
            else if (task.phase === 'EXTRACTING' && task.sourceTotal > 0) row.progressNode.value = Math.min(1, task.sourceBytes / task.sourceTotal);
            else row.progressNode.removeAttribute('value');
            row.progressNode.style.width = '100%';
            row.progressNode.setAttribute('aria-label', task.title + '传输进度');
            row.fileNode.textContent = task.file;
            row.actionsNode.querySelectorAll('button').forEach(function (button) {
                var operation = button.dataset.operation;
                var visible = operation === 'pause' ? task.state === 'RUNNING' : operation === 'resume' ? task.state === 'PAUSED' : operation === 'retry' ? task.state === 'FAILED' || task.state === 'CANCELLED' : ['RUNNING', 'PAUSED', 'QUEUED'].includes(task.state);
                button.classList.toggle('hide', !visible); button.disabled = !visible;
            });
        });
        this.rows.forEach(function (row, id) { if (!remaining.has(id)) { row.remove(); self.rows.delete(id); } });
        if (!tasks.length) container.textContent = '暂无任务';
    };
    DownloadView.prototype.onResume = function (options) {
        BaseView.prototype.onResume.apply(this, arguments);
        loading.hide();
        var self = this;
        var api = this.params.serverId ? connectionManager.getApiClient(this.params.serverId) : connectionManager.currentApiClient();
        if (!api || !api.accessToken()) { this.message('请先登录 Emby 服务器'); return; }
        this.call('configure', { serverUrl: api.serverAddress(), accessToken: api.accessToken(), userId: api.getCurrentUserId(),
            clientName: api.appName(), clientVersion: api.appVersion(), deviceName: api.deviceName(), deviceId: api.deviceId() }).then(function (settings) {
            self.view.querySelector('.downloadDirectory').value = settings.directory;
            self.view.querySelector('.downloadMode').value = settings.mode;
            self.view.querySelector('.downloadConcurrent').value = String(settings.concurrent);
            if (self.unsubscribe) self.unsubscribe();
            self.unsubscribe = window.EmbyDownloadBridge.subscribe(function (event) {
                if (event.event === 'tasks') self.renderTasks(event.tasks);
                if (event.event === 'error') self.message(event.error);
            });
            self.message('已连接下载后台');
            self.refreshTasks();
            if (options.refresh && self.params.itemId) self.loadItem(self.params.itemId);
        }).catch(function (error) { self.message(error.message); });
    };
    DownloadView.prototype.onPause = function () {
        if (this.unsubscribe) { this.unsubscribe(); this.unsubscribe = null; }
        BaseView.prototype.onPause.apply(this, arguments);
    };
    DownloadView.prototype.destroy = function () {
        if (this.unsubscribe) this.unsubscribe();
        BaseView.prototype.destroy.apply(this, arguments);
    };
    return DownloadView;
});
