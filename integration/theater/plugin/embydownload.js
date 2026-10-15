define(['pluginManager', 'embyRouter', 'connectionManager', 'emby-button'], function (pluginManager, embyRouter, connectionManager) {
    'use strict';

    return function () {
        var self = this;
        self.id = 'embydownload';
        self.name = 'EmbyDownload';
        self.type = 'general';
        self.getRoutes = function () {
            return [{ path: 'embydownload/download.html', controller: pluginManager.mapPath(self, 'embydownload/download.js'),
                type: 'settings', title: '下载与字幕', category: 'Playback', icon: 'file_download',
                settingsTheme: true, transition: 'slide', adjustHeaderForEmbeddedScroll: true }];
        };

        function open(itemId, serverId) {
            var route = pluginManager.mapRoute(self, 'embydownload/download.html');
            if (itemId) route += '?itemId=' + encodeURIComponent(itemId) + '&serverId=' + encodeURIComponent(serverId || '');
            embyRouter.show(route);
        }

        function addControls() {
            var header = document.querySelector('.headerRight');
            if (header && !header.querySelector('.embydownloadHeader')) {
                var button = document.createElement('button', { is: 'paper-icon-button-light' });
                button.setAttribute('is', 'paper-icon-button-light');
                button.className = 'headerButton md-icon embydownloadHeader';
                button.type = 'button';
                button.title = '下载与字幕';
                button.setAttribute('aria-label', '下载与字幕');
                button.textContent = '\ue2c4';
                button.addEventListener('click', function () { open(); });
                header.insertBefore(button, header.firstChild);
            }
            var itemPage = window.itemPage;
            var container = itemPage && itemPage.mainSection && itemPage.mainSection.querySelector('.mainDetailButtons');
            if (container && !container.querySelector('.embydownloadItem')) {
                var itemButton = document.createElement('button', { is: 'emby-button' });
                itemButton.setAttribute('is', 'emby-button');
                itemButton.type = 'button';
                itemButton.className = 'emby-button raised embydownloadItem';
                itemButton.textContent = '下载视频 / 音频 / 字幕';
                itemButton.addEventListener('click', function () {
                    var params = window.itemPage && window.itemPage.params;
                    if (params) open(params.id, params.serverId);
                });
                container.appendChild(itemButton);
            }
        }
        document.addEventListener('viewshow', addControls);
        var scheduled = false;
        var observer = new MutationObserver(function () {
            if (scheduled) return;
            scheduled = true;
            requestAnimationFrame(function () { scheduled = false; addControls(); });
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
        window.addEventListener('beforeunload', function () { observer.disconnect(); });
        addControls();
    };
});
