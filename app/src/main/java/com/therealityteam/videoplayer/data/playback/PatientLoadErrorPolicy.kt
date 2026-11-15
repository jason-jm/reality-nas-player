package com.therealityteam.videoplayer.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException

/**
 * How long a film that is playing waits out its share: a Wi-Fi blip on the headset, a NAS waking a
 * disk. Media3 gave up after three quick tries — three seconds of airplane mode ended a film with
 * 「读取不到这个文件」 while half a minute of it sat buffered, and it stayed ended after the network
 * came back. Once a film is playing ([patient]) a read that failed for the network is tried again,
 * a second apart and then five, for about two minutes: the buffer plays on meanwhile, and the
 * player waits (加载中) only when it runs out. Opening a file stays quick to fail — a share that is
 * off says so at once. What no retry mends — a file gone, one that is not what it claims — fails
 * at once either way.
 */
@OptIn(UnstableApi::class)
class PatientLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(PATIENT_TRIES) {

    /** Whether the file has started playing: only then is the network waited for. */
    @Volatile var patient = false

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        val hopeless = e is ParserException || e is FileNotFoundException || e is Loader.UnexpectedLoaderException ||
            e is HttpDataSource.CleartextNotPermittedException
        return retryDelayMs(hopeless, (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode, loadErrorInfo.errorCount, patient)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (patient) PATIENT_TRIES else QUICK_TRIES

    internal companion object {
        /**
         * The wait before asking again (ms), or [C.TIME_UNSET] for not at all: after a read that failed
         * [hopeless]ly, or with the server's [status], the [errorCount]th time, for a film [patient] or opening.
         */
        fun retryDelayMs(hopeless: Boolean, status: Int?, errorCount: Int, patient: Boolean): Long {
            if (hopeless || status in GONE) return C.TIME_UNSET
            // The loopback could not reach the share or server at all (it says 504) and has just tried: opening, a
            // NAS that is off says so at once rather than after three more tries (26 s on the headset).
            if (!patient && status == 504) return C.TIME_UNSET
            if (!patient) return if (errorCount <= QUICK_TRIES) minOf(errorCount * 1000L, 3000L) else C.TIME_UNSET
            return if (errorCount <= PATIENT_TRIES) minOf(errorCount * 1000L, 5000L) else C.TIME_UNSET
        }

        /** About two minutes: 1 + 2 + 3 + 4 s, then every 5 s. */
        const val PATIENT_TRIES = 28
        const val QUICK_TRIES = 3
        /** A file the server says is not there (or no longer that long): no wait brings it back. */
        val GONE = setOf(404, 410, 416)
    }
}
