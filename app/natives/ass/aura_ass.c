/*
 * libass for the subtitles Media3 plays (AssRenderer.kt): a fansub's .ass drawn as its author set
 * it — signs over the signs they translate, an opening's karaoke, lines in layers — into a picture
 * the player lays over the video, rather than read as plain lines.
 *
 * libass is the one inside libmpv.so (it exports ass_*), so nothing more of it is linked here; built
 * by app/natives/build-ass.sh. Every call on one handle comes from one thread (libass is not
 * thread-safe per renderer). Kept in the app under the same terms as the rest of its native code.
 */
#include <jni.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <ass/ass.h>

#define TAG "AuraAss"

typedef struct {
    ASS_Library *lib;
    ASS_Renderer *renderer;
    ASS_Track *track;
    ASS_Image *images; /* from the last render, valid until the next */
    int box[4];        /* their bounds: x, y, w, h */
    char *conf;        /* the fonts.conf and the family fonts fall back to, to set the fonts again */
    char *family;
} Ctx;

static void on_message(int level, const char *fmt, va_list args, void *data) {
    (void) data;
    /* libass's 0–7: errors and warnings only, the rest is chatter. */
    if (level > 2) return;
    __android_log_vprint(level <= 1 ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, fmt, args);
}

static Ctx *ctx_of(jlong handle) { return (Ctx *) (intptr_t) handle; }

JNIEXPORT jlong JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_create(JNIEnv *env, jclass cls, jstring fontsConf, jstring family) {
    (void) cls;
    Ctx *c = calloc(1, sizeof(Ctx));
    if (!c) return 0;
    c->lib = ass_library_init();
    if (!c->lib) { free(c); return 0; }
    ass_set_message_cb(c->lib, on_message, NULL);
    /* Fonts a script carries in its own [Fonts] section. */
    ass_set_extract_fonts(c->lib, 1);
    c->renderer = ass_renderer_init(c->lib);
    if (!c->renderer) { ass_library_done(c->lib); free(c); return 0; }
    /* 字幕大小 enlarges what looks like dialogue, not a sign set to fit its sign (mpv's way too). */
    ass_set_selective_style_override_enabled(c->renderer, ASS_OVERRIDE_BIT_SELECTIVE_FONT_SCALE);
    if (fontsConf) {
        const char *conf = (*env)->GetStringUTFChars(env, fontsConf, NULL);
        c->conf = strdup(conf);
        (*env)->ReleaseStringUTFChars(env, fontsConf, conf);
    }
    if (family) {
        const char *fam = (*env)->GetStringUTFChars(env, family, NULL);
        c->family = strdup(fam);
        (*env)->ReleaseStringUTFChars(env, family, fam);
    }
    /* fontconfig over the headset's fonts: the first time a scan (seconds), then its cache. */
    ass_set_fonts(c->renderer, NULL, c->family ? c->family : "sans-serif", ASS_FONTPROVIDER_FONTCONFIG, c->conf, 1);
    return (jlong) (intptr_t) c;
}

JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_setSize(JNIEnv *env, jclass cls, jlong handle,
                                                                  jint frameW, jint frameH, jint videoW, jint videoH) {
    (void) env; (void) cls;
    Ctx *c = ctx_of(handle);
    ass_set_frame_size(c->renderer, frameW, frameH);
    if (videoW > 0 && videoH > 0) ass_set_storage_size(c->renderer, videoW, videoH);
}

JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_setFontScale(JNIEnv *env, jclass cls, jlong handle, jdouble scale) {
    (void) env; (void) cls;
    ass_set_font_scale(ctx_of(handle)->renderer, scale);
}

static void drop_track(Ctx *c) {
    c->images = NULL;
    if (c->track) ass_free_track(c->track);
    c->track = NULL;
}

/** A whole script (a .ass file), in UTF-8. */
JNIEXPORT jboolean JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_loadScript(JNIEnv *env, jclass cls, jlong handle, jbyteArray utf8) {
    (void) cls;
    Ctx *c = ctx_of(handle);
    drop_track(c);
    jsize n = (*env)->GetArrayLength(env, utf8);
    char *buf = malloc((size_t) n + 1);
    if (!buf) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, utf8, 0, n, (jbyte *) buf);
    buf[n] = 0;
    c->track = ass_read_memory(c->lib, buf, (size_t) n, NULL);
    free(buf);
    return c->track ? JNI_TRUE : JNI_FALSE;
}

/** A stream's track: its header (an MKV's CodecPrivate) now, its events as they are read ([addEvent]). */
JNIEXPORT jboolean JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_newTrack(JNIEnv *env, jclass cls, jlong handle, jbyteArray header) {
    (void) cls;
    Ctx *c = ctx_of(handle);
    drop_track(c);
    c->track = ass_new_track(c->lib);
    if (!c->track) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, header);
    jbyte *data = (*env)->GetByteArrayElements(env, header, NULL);
    ass_process_codec_private(c->track, (const char *) data, n);
    (*env)->ReleaseByteArrayElements(env, header, data, JNI_ABORT);
    return JNI_TRUE;
}

/** One event as an MKV block holds it ("ReadOrder,Layer,Style,…,Text"); one already had is dropped. */
JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_addEvent(JNIEnv *env, jclass cls, jlong handle, jbyteArray chunk,
                                                                   jlong startMs, jlong durationMs) {
    (void) cls;
    Ctx *c = ctx_of(handle);
    if (!c->track) return;
    jsize n = (*env)->GetArrayLength(env, chunk);
    jbyte *data = (*env)->GetByteArrayElements(env, chunk, NULL);
    ass_process_chunk(c->track, (const char *) data, n, startMs, durationMs);
    (*env)->ReleaseByteArrayElements(env, chunk, data, JNI_ABORT);
}

/** A font the video file carries (an MKV attachment). */
JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_addFont(JNIEnv *env, jclass cls, jlong handle, jstring name, jbyteArray font) {
    (void) cls;
    Ctx *c = ctx_of(handle);
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    jsize len = (*env)->GetArrayLength(env, font);
    jbyte *data = (*env)->GetByteArrayElements(env, font, NULL);
    ass_add_font(c->lib, n, (const char *) data, len);
    (*env)->ReleaseByteArrayElements(env, font, data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, n);
}

/** Fonts added or cleared since the renderer chose its own are used once it chooses again. */
JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_refreshFonts(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    Ctx *c = ctx_of(handle);
    ass_set_fonts(c->renderer, NULL, c->family ? c->family : "sans-serif", ASS_FONTPROVIDER_FONTCONFIG, c->conf, 0);
}

/**
 * The subtitles at [timeMs]: 0 when nothing changed since the last call (unless [force]), 1 when
 * there is something to draw — [box] gets its bounds in the frame, and [copy] draws it — 2 when
 * there is nothing.
 */
JNIEXPORT jint JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_render(JNIEnv *env, jclass cls, jlong handle, jlong timeMs,
                                                                 jboolean force, jintArray box) {
    (void) cls;
    Ctx *c = ctx_of(handle);
    if (!c->track) { c->images = NULL; return 2; }
    int changed = 0;
    ASS_Image *img = ass_render_frame(c->renderer, c->track, timeMs, &changed);
    c->images = img;
    if (!changed && !force) return 0;
    int x0 = 1 << 30, y0 = 1 << 30, x1 = -1, y1 = -1;
    for (ASS_Image *i = img; i; i = i->next) {
        if (i->w <= 0 || i->h <= 0 || (i->color & 0xFF) == 0xFF) continue;
        if (i->dst_x < x0) x0 = i->dst_x;
        if (i->dst_y < y0) y0 = i->dst_y;
        if (i->dst_x + i->w > x1) x1 = i->dst_x + i->w;
        if (i->dst_y + i->h > y1) y1 = i->dst_y + i->h;
    }
    if (x1 <= x0 || y1 <= y0) return 2;
    c->box[0] = x0; c->box[1] = y0; c->box[2] = x1 - x0; c->box[3] = y1 - y0;
    (*env)->SetIntArrayRegion(env, box, 0, 4, c->box);
    return 1;
}

/**
 * The last render's images blended into [bitmap] — an ARGB_8888 the size of its box, which it
 * covers from the box's corner — over transparency, premultiplied as Android's bitmaps are.
 */
JNIEXPORT jboolean JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_copy(JNIEnv *env, jclass cls, jlong handle, jobject bitmap) {
    (void) cls;
    Ctx *c = ctx_of(handle);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    const int bw = (int) info.width, bh = (int) info.height;
    for (int y = 0; y < bh; y++) memset((uint8_t *) pixels + (size_t) y * info.stride, 0, (size_t) bw * 4);
    const int ox = c->box[0], oy = c->box[1];
    for (ASS_Image *i = c->images; i; i = i->next) {
        const uint32_t col = i->color;
        const unsigned opacity = 255u - (col & 0xFFu);
        if (opacity == 0 || i->w <= 0 || i->h <= 0) continue;
        const unsigned r = col >> 24, g = (col >> 16) & 0xFF, b = (col >> 8) & 0xFF;
        for (int y = 0; y < i->h; y++) {
            const int ty = i->dst_y - oy + y;
            if (ty < 0 || ty >= bh) continue;
            const uint8_t *src = i->bitmap + (size_t) y * i->stride;
            uint8_t *dst = (uint8_t *) pixels + (size_t) ty * info.stride;
            for (int x = 0; x < i->w; x++) {
                const int tx = i->dst_x - ox + x;
                if (tx < 0 || tx >= bw || src[x] == 0) continue;
                /* This image's coverage here, then "over" in premultiplied RGBA. */
                const unsigned a = (src[x] * opacity + 127u) / 255u;
                const unsigned keep = 255u - a;
                uint8_t *p = dst + tx * 4;
                p[0] = (uint8_t) ((r * a + p[0] * keep + 127u) / 255u);
                p[1] = (uint8_t) ((g * a + p[1] * keep + 127u) / 255u);
                p[2] = (uint8_t) ((b * a + p[2] * keep + 127u) / 255u);
                p[3] = (uint8_t) (a + (p[3] * keep + 127u) / 255u);
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

/** The fonts added so far are another file's. */
JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_clearFonts(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    ass_clear_fonts(ctx_of(handle)->lib);
}

JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_AssNative_free(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    Ctx *c = ctx_of(handle);
    if (!c) return;
    drop_track(c);
    ass_renderer_done(c->renderer);
    ass_library_done(c->lib);
    free(c->conf);
    free(c->family);
    free(c);
}
