package app.wuad.core

object MihomoNative {
    @Volatile
    var loaded: Boolean = false
        private set

    fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("mihomo_exec")
            loaded = true
        }
    }

    external fun start(binPath: String, workDir: String, fd: Int): Int
    external fun stop(pid: Int)
    external fun kill(pid: Int)
}
