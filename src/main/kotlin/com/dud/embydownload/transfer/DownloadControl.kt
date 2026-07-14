package com.dud.embydownload.transfer

import java.util.concurrent.atomic.AtomicBoolean

class DownloadCancelledException : RuntimeException("下载已取消")

class DownloadControl {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val lock = Object()

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        if (cancelled.get()) return
        paused.set(false)
        synchronized(lock) { lock.notifyAll() }
    }

    fun cancel() {
        cancelled.set(true)
        paused.set(false)
        synchronized(lock) { lock.notifyAll() }
    }

    fun reset() {
        paused.set(false)
        cancelled.set(false)
    }

    val isPaused: Boolean
        get() = paused.get()

    val isCancelled: Boolean
        get() = cancelled.get()

    /** 在下载循环中调用：暂停时阻塞，取消时抛出异常。 */
    fun check() {
        if (cancelled.get()) throw DownloadCancelledException()
        while (paused.get()) {
            if (cancelled.get()) throw DownloadCancelledException()
            synchronized(lock) {
                if (paused.get() && !cancelled.get()) {
                    try {
                        lock.wait(200)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw DownloadCancelledException()
                    }
                }
            }
        }
        if (cancelled.get()) throw DownloadCancelledException()
    }
}
