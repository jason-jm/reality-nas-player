package com.therealityteam.videoplayer.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.data.repository.SettingsRepository
import com.therealityteam.videoplayer.di.appModule
import com.therealityteam.videoplayer.mainApp
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import kotlinx.coroutines.launch as launchCoroutine

class SpatialApplication : Application() {

    /**
     * 同步 follows the app to the front and to the back: whatever another device watched is taken in
     * as the headset comes back into use, and whatever was watched here is written out as it is put
     * away. Counted over every window's activity, so moving between windows is neither.
     */
    private fun startSync() {
        val sync = GlobalContext.get().get<com.therealityteam.videoplayer.data.sync.HeadsetSync>()
        sync.start()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                if (started++ == 0) {
                    sync.nudge()
                    // 下载到本机 carries on whichever window comes up first — the player opened from a
                    // cast or the system's 继续观看 too, not only the library's (which kicks it as well).
                    runCatching { GlobalContext.get().get<com.therealityteam.videoplayer.data.download.Downloader>().kick() }
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                if (--started == 0) sync.nudge(minGapMs = 0)
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    /**
     * 接收投屏 runs while the setting is on and any of the app's windows is up — the library's or the
     * player's, which replace one another as a cast opens (the window it was tied to went away the
     * moment the cast it had received began to play). Put away, the app leaves the network.
     */
    private fun startCast() {
        val receiver = GlobalContext.get().get<CastReceiver>()
        val settings = GlobalContext.get().get<SettingsRepository>()
        val main = android.os.Handler(mainLooper)
        var started = 0
        var on = settings.get().castReceiver
        fun apply() {
            if (on && started > 0) receiver.start(getString(com.therealityteam.videoplayer.R.string.set_cast_name, android.os.Build.MODEL))
            else receiver.stop()
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { started++; apply() }
            override fun onActivityStopped(activity: android.app.Activity) {
                started--
                // A window giving way to another stops one before the other starts: wait a beat.
                main.postDelayed({ if (started == 0) apply() }, CAST_LINGER_MS)
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launchCoroutine {
            settings.settings.collect { s ->
                if (s.castReceiver != on) { on = s.castReceiver; apply() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Before anything reads the library: the shared core's Android hooks (logcat, ICU, Keystore).
        CorePlatform.install()
        // SFTP servers' keys as first seen, kept with the app's data (trust on first use).
        com.therealityteam.videoplayer.data.source.PinnedHostKeys.file = java.io.File(filesDir, "aura/sftp_known_hosts")
        com.therealityteam.videoplayer.util.UnreachableHosts.file = java.io.File(filesDir, "aura/unreachable_hosts.tsv")
        // AI 识别 is gone (it never beat the rules, and could pin an invented name on a home video): a model
        // it downloaded — 0.4 to 2 GB — goes with it, off the main thread.
        Thread({ runCatching { getExternalFilesDir(null)?.let { java.io.File(it, "llm") }?.takeIf { it.exists() }?.deleteRecursively() } }, "aura-old-model").start()
        startKoin {
            androidContext(this@SpatialApplication)
            modules(appModule)
        }
        // Long-lived scope shared across the Shared-Space and Full-Space composable trees.
        GlobalContext.get().createScope(AURA_SESSION_SCOPE_ID, named(AURA_SESSION_SCOPE_ID))
        // Force settings init so a saved TMDB key is applied to scraping at startup.
        GlobalContext.get().get<SettingsRepository>()
        startSync()
        startCast()
        launch(::mainApp)
    }

    // 界面语言 (the per-app language) or the headset's own changed: Locale.getDefault() is the new one now.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { GlobalContext.get().get<SettingsRepository>().languageChanged() }
    }

    private companion object {
        const val CAST_LINGER_MS = 3_000L
    }
}
