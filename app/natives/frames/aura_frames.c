/*
 * Keyframes out of a video as small pictures, by the FFmpeg inside the app (the libav*.so that
 * libmpv.so plays with): 章节 pictures for the files the platform's software decoders cannot read —
 * 10-bit HEVC (UHD discs, x265 encodes), 10-bit H.264, RealVideo, VC-1 and the rest mpv plays.
 *
 * Only keyframes are decoded, one at a time on one thread, so a film playing beside it — in software,
 * when mpv plays it — loses next to nothing. Every call on one handle comes from one thread; abort()
 * may come from any, and makes a read under way give up. Built by app/natives/build-frames.sh; kept
 * in the app under the same terms as the rest of its native code, linked against the LGPL FFmpeg.
 */
#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>

#define TAG "AuraFrames"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/*
 * How many video packets one picture may read past its seek looking for a keyframe in its chapter:
 * twenty seconds of a 24 fps film — an anime encode's keyframes can be ten seconds apart.
 */
#define MAX_PACKETS 480

/*
 * How long one picture may take. A seek past a file's last indexed keyframe reads the rest of the
 * file looking for one — minutes, over a share, at the end of a film.
 */
#define PICTURE_US 10000000

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    struct SwsContext *sws;
    AVPacket *pkt;
    AVFrame *frame;
    int stream;
    atomic_int aborted;
    /* When the picture being read is given up (av_gettime_relative), 0 between pictures. */
    int64_t deadline;
} Grab;

static Grab *grab_of(jlong handle) { return (Grab *) (intptr_t) handle; }

static int on_interrupt(void *opaque) {
    Grab *g = opaque;
    return atomic_load(&g->aborted) || (g->deadline && av_gettime_relative() > g->deadline);
}

static void grab_free(Grab *g) {
    if (!g) return;
    sws_freeContext(g->sws);
    av_frame_free(&g->frame);
    av_packet_free(&g->pkt);
    avcodec_free_context(&g->dec);
    avformat_close_input(&g->fmt);
    free(g);
}

/* A software decoder for [id]: never a MediaCodec wrapper, which would take a hardware decoder. */
static const AVCodec *software_decoder(enum AVCodecID id) {
    const AVCodec *c = NULL;
    void *it = NULL;
    while ((c = av_codec_iterate(&it))) {
        if (c->id != id || !av_codec_is_decoder(c)) continue;
        if (c->capabilities & (AV_CODEC_CAP_HARDWARE | AV_CODEC_CAP_EXPERIMENTAL)) continue;
        if (strstr(c->name, "mediacodec")) continue;
        return c;
    }
    return NULL;
}

/* A handle; 0 when the file could not be read (the share away, a timeout), -1 when it was read and
 * has no picture this FFmpeg decodes. */
JNIEXPORT jlong JNICALL
Java_com_therealityteam_videoplayer_data_playback_FrameNative_open(JNIEnv *env, jclass cls, jstring jurl) {
    (void) cls;
    Grab *g = calloc(1, sizeof(Grab));
    if (!g) return 0;
    atomic_init(&g->aborted, 0);
    g->fmt = avformat_alloc_context();
    if (!g->fmt) { free(g); return 0; }
    g->fmt->interrupt_callback.callback = on_interrupt;
    g->fmt->interrupt_callback.opaque = g;

    AVDictionary *opts = NULL;
    /* µs: a share that stops answering. http keeps no timeout of its own; "timeout" goes on to
     * the socket under it, which otherwise waits for ever. */
    av_dict_set(&opts, "rw_timeout", "8000000", 0);
    av_dict_set(&opts, "timeout", "8000000", 0);
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    int r = avformat_open_input(&g->fmt, url, NULL, &opts);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
    av_dict_free(&opts);
    if (r < 0) { LOGW("open: %s", av_err2str(r)); g->fmt = NULL; grab_free(g); return 0; }
    g->fmt->probesize = 4 << 20;
    if ((r = avformat_find_stream_info(g->fmt, NULL)) < 0) { LOGW("stream info: %s", av_err2str(r)); grab_free(g); return 0; }

    /* The picture's stream — not a cover image attached to the file. */
    g->stream = -1;
    int best = av_find_best_stream(g->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    for (unsigned i = 0; i < g->fmt->nb_streams; i++) {
        AVStream *st = g->fmt->streams[i];
        int video = st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && !(st->disposition & AV_DISPOSITION_ATTACHED_PIC);
        if (video && (g->stream < 0 || (int) i == best)) g->stream = (int) i;
        /* The rest is skipped by the demuxer, not handed over. */
        st->discard = AVDISCARD_ALL;
    }
    if (g->stream < 0) { LOGW("no video stream"); grab_free(g); return -1; }
    AVStream *st = g->fmt->streams[g->stream];
    st->discard = AVDISCARD_DEFAULT;

    const AVCodec *codec = software_decoder(st->codecpar->codec_id);
    if (!codec) { LOGW("no decoder for %s", avcodec_get_name(st->codecpar->codec_id)); grab_free(g); return -1; }
    g->dec = avcodec_alloc_context3(codec);
    if (!g->dec || avcodec_parameters_to_context(g->dec, st->codecpar) < 0) { grab_free(g); return 0; }
    g->dec->thread_count = 1;
    g->dec->skip_loop_filter = AVDISCARD_ALL;
    if ((r = avcodec_open2(g->dec, codec, NULL)) < 0) { LOGW("decoder: %s", av_err2str(r)); grab_free(g); return -1; }
    g->pkt = av_packet_alloc();
    g->frame = av_frame_alloc();
    if (!g->pkt || !g->frame) { grab_free(g); return 0; }
    return (jlong) (intptr_t) g;
}

/* The colours a frame was coded in, as swscale names them. */
static int sws_space(const AVFrame *f) {
    switch (f->colorspace) {
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: return SWS_CS_BT2020;
        case AVCOL_SPC_BT709: return SWS_CS_ITU709;
        case AVCOL_SPC_SMPTE170M:
        case AVCOL_SPC_BT470BG: return SWS_CS_ITU601;
        default: return f->height >= 720 ? SWS_CS_ITU709 : SWS_CS_ITU601;
    }
}

/*
 * [w, h, pq, pixels…]: the frame [maxWidth] wide at most (its display shape kept), as Android's
 * ARGB ints; pq is 1 when the picture is HDR10, its values still PQ-coded R'G'B' for the caller to
 * bring down.
 */
static jintArray to_pixels(JNIEnv *env, Grab *g, AVFrame *f, int maxWidth) {
    int w = f->width, h = f->height;
    if (w <= 0 || h <= 0) return NULL;
    double sar = (f->sample_aspect_ratio.num > 0 && f->sample_aspect_ratio.den > 0) ? av_q2d(f->sample_aspect_ratio) : 1.0;
    double shown = w * sar;
    int outW = shown < maxWidth ? (int) shown : maxWidth;
    if (outW < 2) outW = 2;
    int outH = (int) ((double) h * outW / shown + 0.5);
    if (outH < 2) outH = 2;
    g->sws = sws_getCachedContext(g->sws, w, h, (enum AVPixelFormat) f->format, outW, outH, AV_PIX_FMT_BGRA,
                                  SWS_BILINEAR, NULL, NULL, NULL);
    if (!g->sws) return NULL;
    sws_setColorspaceDetails(g->sws, sws_getCoefficients(sws_space(f)), f->color_range == AVCOL_RANGE_JPEG,
                             sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16);
    size_t count = (size_t) outW * outH;
    uint32_t *px = malloc(count * 4);
    if (!px) return NULL;
    uint8_t *dst[4] = {(uint8_t *) px, NULL, NULL, NULL};
    int stride[4] = {outW * 4, 0, 0, 0};
    sws_scale(g->sws, (const uint8_t *const *) f->data, f->linesize, 0, h, dst, stride);
    jintArray out = (*env)->NewIntArray(env, (jsize) (count + 3));
    if (out) {
        jint head[3] = {outW, outH, f->color_trc == AVCOL_TRC_SMPTE2084 ? 1 : 0};
        (*env)->SetIntArrayRegion(env, out, 0, 3, head);
        (*env)->SetIntArrayRegion(env, out, 3, (jsize) count, (const jint *) px);
    }
    free(px);
    return out;
}

/* The next packet of the picture's stream; 0 at the end, or when the read failed. */
static int next_video_packet(Grab *g) {
    while (av_read_frame(g->fmt, g->pkt) >= 0) {
        if (g->pkt->stream_index == g->stream) return 1;
        av_packet_unref(g->pkt);
    }
    return 0;
}

static int64_t packet_time(const AVPacket *p) { return p->pts != AV_NOPTS_VALUE ? p->pts : p->dts; }

/* [g->pkt], a keyframe, decoded alone: the decoder drained at once, as an H.264 picture held for
 * the frames after it would only come out at the end of the stream. */
static jintArray decode_alone(JNIEnv *env, Grab *g, int maxWidth) {
    avcodec_flush_buffers(g->dec);
    avcodec_send_packet(g->dec, g->pkt);
    av_packet_unref(g->pkt);
    avcodec_send_packet(g->dec, NULL);
    jintArray out = NULL;
    if (avcodec_receive_frame(g->dec, g->frame) == 0) {
        out = to_pixels(env, g, g->frame, maxWidth);
        av_frame_unref(g->frame);
    }
    avcodec_flush_buffers(g->dec);
    return out;
}

static jintArray picture_at(JNIEnv *env, Grab *g, int64_t at, int64_t notBefore, int maxWidth) {
    /* The keyframe at or before [at], when it is in the chapter: the index knows where it is. */
    if (av_seek_frame(g->fmt, g->stream, at, AVSEEK_FLAG_BACKWARD) >= 0 && next_video_packet(g)) {
        int64_t t = packet_time(g->pkt);
        if ((g->pkt->flags & AV_PKT_FLAG_KEY) && t != AV_NOPTS_VALUE && t >= notBefore) {
            jintArray out = decode_alone(env, g, maxWidth);
            if (out) return out;
        } else {
            av_packet_unref(g->pkt);
        }
    }
    /* Else the first keyframe after the chapter's start, read on to from where the index puts it. */
    if (avformat_seek_file(g->fmt, g->stream, notBefore, at, INT64_MAX, 0) < 0) return NULL;
    for (int packets = 0; packets < MAX_PACKETS && next_video_packet(g); packets++) {
        int64_t t = packet_time(g->pkt);
        if (!(g->pkt->flags & AV_PKT_FLAG_KEY) || (t != AV_NOPTS_VALUE && t < notBefore)) {
            av_packet_unref(g->pkt);
            continue;
        }
        /* A keyframe that gives no picture alone (an open GOP's) is passed for the next. */
        jintArray out = decode_alone(env, g, maxWidth);
        if (out) return out;
    }
    return NULL;
}

JNIEXPORT jintArray JNICALL
Java_com_therealityteam_videoplayer_data_playback_FrameNative_frameAt(
    JNIEnv *env, jclass cls, jlong handle, jlong ms, jlong notBeforeMs, jint maxWidth) {
    (void) cls;
    Grab *g = grab_of(handle);
    if (!g || atomic_load(&g->aborted)) return NULL;
    AVStream *st = g->fmt->streams[g->stream];
    int64_t at = av_rescale_q(ms * 1000, AV_TIME_BASE_Q, st->time_base);
    int64_t notBefore = av_rescale_q(notBeforeMs * 1000, AV_TIME_BASE_Q, st->time_base);
    if (st->start_time != AV_NOPTS_VALUE) { at += st->start_time; notBefore += st->start_time; }
    g->deadline = av_gettime_relative() + PICTURE_US;
    jintArray out = picture_at(env, g, at, notBefore, maxWidth);
    if (!out && g->deadline && av_gettime_relative() > g->deadline) LOGW("gave up on %lld ms", (long long) ms);
    g->deadline = 0;
    return out;
}

JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_FrameNative_abort(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    Grab *g = grab_of(handle);
    if (g) atomic_store(&g->aborted, 1);
}

JNIEXPORT void JNICALL
Java_com_therealityteam_videoplayer_data_playback_FrameNative_close(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    grab_free(grab_of(handle));
}
