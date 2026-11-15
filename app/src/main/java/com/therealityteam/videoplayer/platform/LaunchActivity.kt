package com.therealityteam.videoplayer.platform

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class LaunchActivity : SpatialLaunchActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consume(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        android.util.Log.i("AuraLink", "consume ${intent?.extras?.keySet()} in ${System.identityHashCode(this)} task $taskId")
        // 打开方式: a video handed over by another app (ACTION_VIEW with a file:// or content:// uri).
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                DeepLink.playUriDisplayName.value = displayNameOf(uri)
                DeepLink.playUriRequest.value = uri.toString()
            }
        }
        // Everything below is automation (build.gradle.kts, AUTOMATION): heard by the debug build only.
        if (!com.therealityteam.videoplayer.BuildConfig.AUTOMATION) return
        intent?.getStringExtra("aura.play")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.playRequest.value = it
        }
        intent?.getStringExtra("aura.play_uri")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.playUriRequest.value = it
        }
        intent?.getStringExtra("aura.open_key")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.openKeyRequest.value = it
        }
        intent?.getStringExtra("aura.play_as")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.playAsRequest.value = it
        }
        intent?.getStringExtra("aura.seek_ms")?.toLongOrNull()?.let {
            DeepLink.seekMsRequest.value = it
        }
        intent?.getStringExtra("aura.crop")?.takeIf { it.isNotBlank() }?.let { DeepLink.cropRequest.value = it }
        intent?.getStringExtra("aura.frame_dump")?.takeIf { it.isNotBlank() }?.let { DeepLink.frameDumpRequest.value = it }
        intent?.getStringExtra("aura.mpv_set")?.takeIf { it.isNotBlank() }?.let { DeepLink.mpvSet = it }
        when (intent?.getStringExtra("aura.cast")) { "on" -> DeepLink.castRequest.value = true; "off" -> DeepLink.castRequest.value = false }
        when (intent?.getStringExtra("aura.engine")) { "exo" -> DeepLink.engineRequest.value = true; "system" -> DeepLink.engineRequest.value = false }
        intent?.getStringExtra("aura.jank")?.takeIf { it.isNotBlank() }?.let { v ->
            // A new Pair each time (its label made unique), so the same request twice runs twice.
            v.substringBefore(':').toLongOrNull()?.let { DeepLink.jankRequest.value = it to (v.substringAfter(':', "") + " #" + System.nanoTime() % 1000) }
        }
        if (intent?.getStringExtra("aura.snapshot") != null) {
            DeepLink.snapshotRequest.value = true
        }
        intent?.getStringExtra("aura.audio_delay_ms")?.toLongOrNull()?.let {
            DeepLink.audioDelayMsRequest.value = it
        }
        intent?.getStringExtra("aura.speed")?.toFloatOrNull()?.takeIf { it in 0.25f..4f }?.let {
            DeepLink.speedRequest.value = it
        }
        intent?.getStringExtra("aura.skip")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.skipRequest.value = it
        }
        if (intent?.getStringExtra("aura.hover_debug") == "1") DeepLink.hoverDebug = true
        intent?.getStringExtra("aura.card_menu")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.cardMenuRequest.value = it
        }
        intent?.getStringExtra("aura.sleep")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.sleepRequest.value = it
        }
        if (intent?.getStringExtra("aura.close_player") == "1") DeepLink.closePlayerRequest.value = android.os.SystemClock.elapsedRealtime()
        intent?.getStringExtra("aura.track")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.trackRequest.value = it
        }
        intent?.getStringExtra("aura.add_local")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.addLocalRequest.value = it
        }
        intent?.getStringExtra("aura.remove_source")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.removeSourceRequest.value = it
        }
        intent?.getStringExtra("aura.forget_progress")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.forgetProgressRequest.value = it
        }
        intent?.getStringExtra("aura.frames")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.frameStepRequest.value = it
        }
        intent?.getStringExtra("aura.seek_bench")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.seekBenchRequest.value = it
        }
        if (intent?.getStringExtra("aura.chapter_skip") == "1") DeepLink.chapterSkipRequest.value = true
        intent?.getStringExtra("aura.scrub")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.scrubRequest.value = it
        }
        intent?.getStringExtra("aura.bookmarks")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.bookmarksRequest.value = it
        }
        intent?.getStringExtra("aura.open_menu")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.openMenuRequest.value = it
        }
        intent?.getStringExtra("aura.open")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.openRequest.value = it
        }
        intent?.getStringExtra("aura.detail_sheet")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.detailSheetRequest.value = it
        }
        intent?.getStringExtra("aura.screen")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.screenRequest.value = it
        }
        intent?.getStringExtra("aura.sort")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.sortRequest.value = it
        }
        intent?.getStringExtra("aura.scrape")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.scrapeRequest.value = it
        }
        intent?.getStringExtra("aura.space")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.spaceRequest.value = it
        }
        intent?.getStringExtra("aura.env_lights")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.envLightsRequest.value = it
        }
        intent?.getStringExtra("aura.env_amb")?.takeIf { it.isNotBlank() }?.let { DeepLink.envAmbienceRequest.value = it }
        intent?.getStringExtra("aura.env_fx")?.takeIf { it.isNotBlank() }?.let { DeepLink.envEffectsRequest.value = it }
        intent?.getStringExtra("aura.env_rt_lights")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.envRealtimeLightsRequest.value = it to (intent.getStringExtra("aura.env_rt_lm")?.toFloatOrNull() ?: 683f)
        }
        intent?.getStringExtra("aura.pano")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.panoRequest.value = it
        }
        intent?.getStringExtra("aura.env")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.envRequest.value = it
        }
        intent?.getStringExtra("aura.env_file")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.envRequest.value = it
        }
        intent?.getStringExtra("aura.env_pose")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.envPoseRequest.value = it
        }
        intent?.getStringExtra("aura.env_screen")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.envScreenRequest.value = it
        }
        intent?.getStringExtra("aura.env_capture")?.let {
            DeepLink.envCaptureRequest.value = it == "1"
        }
        intent?.getStringExtra("aura.frame_poster")?.takeIf { it.isNotBlank() }?.let {
            DeepLink.framePosterRequest.value = it
        }
        // `-e aura.meta_language <tag>` (`follow` for 跟随界面语言): 元数据语言 set as its sheet would — a sweep over the app's
        // languages pins it first, or every switch would rewrite the whole library in the next language.
        intent?.getStringExtra("aura.meta_language")?.takeIf { it.isNotBlank() }?.let { tag ->
            runCatching {
                val repo = org.koin.core.context.GlobalContext.get().get<com.therealityteam.videoplayer.data.repository.SettingsRepository>()
                repo.update(repo.get().copy(language = if (tag == "follow") "" else tag))
                android.util.Log.i("AuraI18n", "metadata language set by automation: $tag")
            }
        }
        intent?.getStringExtra("aura.i18n_scan")?.takeIf { it.isNotBlank() }?.let { name ->
            // After this intent's own requests have had a frame to show.
            window.decorView.postDelayed({ I18nScan.run(applicationContext, name) }, 600)
        }
    }

    /** A content:// uri's human name (what a file manager shows), or null for other schemes. */
    private fun displayNameOf(uri: Uri): String? = if (uri.scheme == "content") {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    } else {
        null
    }
}
