package com.therealityteam.videoplayer.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Launch-intent requests handed from [LaunchActivity] to the composition. One use today:
 *
 *   adb shell am start -n <pkg>/.platform.LaunchActivity -e aura.play "关键词"
 *
 * plays the first library item whose title or file name contains the keyword — the
 * automation entry every playback change is verified through (the emulator's spatial
 * windows sit outside `input tap`'s reach), and a scriptable "play this" hook for users.
 */
object DeepLink {
    /** Pending play request (title/filename substring), consumed by AuraApp. */
    val playRequest = MutableStateFlow<String?>(null)

    /**
     * Pending direct-file request (`-e aura.play_uri /path/or/smb://…`): plays a file that
     * is not in the library, as an ad-hoc item — an "open file" ability. Several paths joined
     * by `|` play as one ad-hoc queue, so prev/next (and the media keys) have somewhere to go.
     */
    val playUriRequest = MutableStateFlow<String?>(null)

    /**
     * Automation (`-e aura.play_as <words>` with `aura.play_uri`): the local file plays AS the first
     * library title whose name holds the words — its name, its card, and its progress, which is
     * written to that title — so a flow that needs a library item can be driven without the share
     * the item lives on.
     */
    val playAsRequest = MutableStateFlow<String?>(null)

    /**
     * A card to open, by its key, once the library window is back: the player's 看完了 card naming the
     * title it suggested ([openRequest] matches words; this is the one it means).
     */
    val openKeyRequest = MutableStateFlow<String?>(null)

    /** Optional start position (`-e aura.seek_ms 1653000`) consumed with the next play request. */
    val seekMsRequest = MutableStateFlow<Long?>(null)

    /**
     * `-e aura.seek_bench 15,-15,600,-1200`: once the film plays, those steps (seconds, from where it
     * is) one every 4 s — each seek's picture time logged by the engine (AuraPerf "seek … picture in").
     */
    val seekBenchRequest = MutableStateFlow<String?>(null)

    /** `-e aura.add_local <folder>`: that folder on the headset added as a 本地 source and scanned (for testing a library on a device). */
    val addLocalRequest = MutableStateFlow<String?>(null)

    /** `-e aura.remove_source <folder or address>`: a source added by [addLocalRequest] taken away again. */
    val removeSourceRequest = MutableStateFlow<String?>(null)

    /**
     * `-e aura.forget_progress <id,id,…>`: those files back as never played here — a test's plays undone;
     * `since:<epoch ms>` for every file played since then.
     */
    val forgetProgressRequest = MutableStateFlow<String?>(null)

    /** `-e aura.frames f,f,b`: 逐帧 steps on (f) and back (b), once the film plays — each step's position logged. */
    val frameStepRequest = MutableStateFlow<String?>(null)

    /** Trigger a snapshot of the current frame (`-e aura.snapshot 1`). */
    val snapshotRequest = MutableStateFlow(false)

    /** `-e aura.cast on|off`: 设置 › 播放 › 接收投屏, as its switch — the receiver tried from a computer on the same Wi-Fi. */
    val castRequest = MutableStateFlow<Boolean?>(null)

    /** `-e aura.engine exo|system`: 设置 › 播放 › 引擎, as its switch (it takes effect from the next launch). */
    val engineRequest = MutableStateFlow<Boolean?>(null)

    /** `-e aura.jank <ms>[:<what>]`: frames timed for that long in each window (JankWatch); a fresh pair each launch. */
    val jankRequest = MutableStateFlow<Pair<Long, String>?>(null)

    /** `-e aura.mpv_set 'k=v;k=v'`: mpv properties set before the next file loads — for trying a fix on the headset. */
    @Volatile var mpvSet: String? = null

    /** `-e aura.frame_dump <name>`: the frame on show written out for adb (PlaybackViewModel.dumpFrame). */
    val frameDumpRequest = MutableStateFlow<String?>(null)

    /** `-e aura.crop l,t,r,b|off|probe`: 去黑边 by hand, for checking the panel's crop. */
    val cropRequest = MutableStateFlow<String?>(null)

    /** Set the 音画同步 offset outright (`-e aura.audio_delay_ms 300`), for automation. */
    val audioDelayMsRequest = MutableStateFlow<Long?>(null)

    /** Set the 倍速 outright (`-e aura.speed 2`), for automation — as the 播放 panel's tiles do. */
    val speedRequest = MutableStateFlow<Float?>(null)

    /**
     * Open one of the control bar's menus (`-e aura.open_menu subtitle|subtitle_settings|audio|
     * audio_settings|playback|display|more|info`)
     * once the player is up. The popups sit outside `input tap`'s reach, so this is how a
     * panel is opened for a screenshot without a hand on the controller.
     */
    val openMenuRequest = MutableStateFlow<String?>(null)

    /** Set the playing show's 跳过片头片尾 (`-e aura.skip 90000,105000`: opening end, closing length, ms). */
    val skipRequest = MutableStateFlow<String?>(null)

    /**
     * Drag the seek bar through these points (`-e aura.scrub 0.2,0.5,0.8`, fractions of the length)
     * once a file is playing, holding the last a few seconds before letting go — 拖动预览 for screenshots.
     */
    val scrubRequest = MutableStateFlow<String?>(null)

    /** Press 跳过 on a chapter's 跳过片头 / 跳过片尾 offer once it is up (`-e aura.chapter_skip 1`). */
    val chapterSkipRequest = MutableStateFlow(false)

    /** Mark 书签 in the file that plays (`-e aura.bookmarks 60000,300000`, milliseconds), for screenshots. */
    val bookmarksRequest = MutableStateFlow<String?>(null)

    /**
     * Open a card's long-press menu (`-e aura.card_menu <text>`, `cw:<text>` for a 继续观看 card) — the
     * first card whose name has the text — as a long press would, for screenshots of it.
     */
    val cardMenuRequest = MutableStateFlow<String?>(null)

    /**
     * Draw every hover region the app declares lit, as the system draws one looked at
     * (`-e aura.hover_debug 1`): the emulator takes no gaze from adb, so this is how the room between
     * highlights — and between a highlight and what stands round it — is checked in a screenshot.
     */
    var hoverDebug by mutableStateOf(false)

    /** Set 睡眠定时 once the film is up (`-e aura.sleep END_OF_ITEM|M15|M30|M60|M90|OFF`), for automation. */
    val sleepRequest = MutableStateFlow<String?>(null)

    /**
     * `-e aura.close_player 1`: the player closed as its 返回 closes it (an adb tap never reaches a spatial window) —
     * when it was asked (elapsedRealtime), so that one sent with no player up does not close the next film to open.
     */
    val closePlayerRequest = MutableStateFlow<Long?>(null)

    /**
     * Pick a track as a hand on the track list would (`-e aura.track sub:<text>|sub:off|sub2:<text>|
     * sub2:off|audio:<text>`, several joined by `;`): the first whose name contains the text — the
     * lists are popups `input tap` cannot reach. `sub2` is the 第二字幕, a subtitle file beside the video.
     */
    val trackRequest = MutableStateFlow<String?>(null)

    /** Enter a viewing space on launch (`-e aura.space immersive|cinema|env`), for screenshots of the stages. */
    val spaceRequest = MutableStateFlow<String?>(null)

    /**
     * `-e aura.env_lights on|off|hold`: a layered place's room lights switched, as its 灯光 chip does; "hold" keeps them
     * on even while the film plays (观影灯光 ignored) — the heaviest case, for measuring.
     */
    val envLightsRequest = MutableStateFlow<String?>(null)
    val envLightsHeld = MutableStateFlow(false)

    /** `-e aura.env_amb <gain>|off`: the film's light in a place (动态氛围光) at a gain, or held at the bake's still light. */
    val envAmbienceRequest = MutableStateFlow<String?>(null)

    /** `-e aura.env_fx on|off`: a place's effects (the LED halos) shown or not — for weighing their cost. */
    val envEffectsRequest = MutableStateFlow<String?>(null)

    /**
     * `-e aura.env_rt_lights <lights.json> [-e aura.env_rt_lm <lumens per watt>]`: a hybrid place's room lights made
     * by the headset (tools/envbake/export_hybrid.py) — for comparing them with the baked ones; "off" removes them.
     */
    val envRealtimeLightsRequest = MutableStateFlow<Pair<String, Float>?>(null)
    /**
     * Turn a panorama once it is shown (`-e aura.pano turn:90|recenter`), comma-separated steps: the
     * emulator's head does not turn, so the picture is turned to see behind it.
     */
    val panoRequest = MutableStateFlow<String?>(null)
    /**
     * Enter an environment (`-e aura.env <id>`), or try a model file on the device in one
     * (`-e aura.env_file /data/.../x.glb`), for measuring and placing it. `"'a|b|a'"` enters a, then
     * swaps to b and back on the open stage, each once the place before is up and has been on show 8 s.
     */
    val envRequest = MutableStateFlow<String?>(null)
    /** Re-seat the environment shown (`-e aura.env_pose x,y,z,yaw[,scale]`), live, for placing it by screenshot. */
    val envPoseRequest = MutableStateFlow<String?>(null)
    /**
     * Place the shown environment's screen (`-e aura.env_screen x,y,z,width,facing[,aspect[,backing]]`, in the
     * model's units as the catalogue's), live — for fitting one by screenshot. `off` takes the film off
     * the screen 3 s after it got there, back into the window, as leaving 在屏幕上播放 does.
     */
    val envScreenRequest = MutableStateFlow<String?>(null)
    /** Hide the picture and the bar (`-e aura.env_capture 1|0`), for an environment's thumbnail. */
    val envCaptureRequest = MutableStateFlow<Boolean?>(null)

    /**
     * The wall's sort control (`-e aura.sort open` opens its menu; `-e aura.sort TITLE|RATING|YEAR|
     * WATCHED|RECENT` applies an order) — for screenshots of the library, which `input tap` cannot reach.
     */
    val sortRequest = MutableStateFlow<String?>(null)

    /** Open the detail page of the first card whose title or file name contains the text (`-e aura.open 白色相簿`). */
    val openRequest = MutableStateFlow<String?>(null)

    /**
     * Open one of a page's sheets or dialogs, as its tap would — they sit behind taps that `input tap`
     * cannot reach. On the detail page (with `aura.open`): `more|paths|fix`, `person` (the first credited
     * person with other cards in the library), and `episodes` scrolls to the episode list. With `aura.screen`: `edit|remove` on a source, `language` on settings_scrape,
     * `delete` on settings_ai, `clear` on settings_library, `remove` on settings_sync.
     */
    val detailSheetRequest = MutableStateFlow<String?>(null)

    /**
     * Open a whole screen (`-e aura.screen settings|settings_<page>|sources|sources_add|sources_browse:<host>`,
     * a page being one of the settings page names: `settings_sync`) — the pages that live behind a
     * sidebar tap, which nothing but a hand in the headset can reach, so nothing could review them.
     * `library` goes back to the wall from any page, `view:<shelf>` (a LibSection name: `view:tv`)
     * opens a shelf, and `search:<text>` searches the wall. `filter:<parts>` sets 筛选 (comma-separated:
     * unwatched|in_progress|watched, a year for its decade, any other word a genre) and `filter_menu`
     * opens its panel.
     */
    val screenRequest = MutableStateFlow<String?>(null)

    /**
     * Start a scrape pass (`-e aura.scrape 1`) — the 刮削 button, which lives on a spatial surface
     * that `input tap` cannot reach, so a change to scraping could only ever be reviewed by asking
     * somebody to press it. The pass is the ordinary one, including the correction it runs first.
     */
    val scrapeRequest = MutableStateFlow<String?>(null)

    /** Draw a frame poster for a local file (`-e aura.frame_poster /path`) and log where it went. */
    val framePosterRequest = MutableStateFlow<String?>(null)

    /** The display name of a content:// [playUriRequest], resolved by the activity, for the title. */
    val playUriDisplayName = MutableStateFlow<String?>(null)
    /** The title a cast came with (接收投屏): shown as the item's name instead of the URL's last segment. */
    val playUriTitle = MutableStateFlow<String?>(null)
    /** The pending playUriRequest came from 接收投屏 (it may come with no title). */
    val playUriCast = MutableStateFlow(false)
}
