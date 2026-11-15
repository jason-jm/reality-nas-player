package com.therealityteam.videoplayer.data.playback

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import java.util.concurrent.TimeUnit

/**
 * SMBClient tuned for video streaming: a 1 MiB read buffer (far fewer round trips than the
 * ~64 KiB default, which matters over a NAT'd / higher-latency link) and generous timeouts so
 * a long sequential read doesn't time out. Used by both playback bridges.
 */
fun streamingSmbClient(): SMBClient =
    SMBClient(
        SmbConfig.builder()
            .withReadBufferSize(1 shl 20)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build(),
    )
