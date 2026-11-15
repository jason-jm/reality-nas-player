package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.scrape.KodiNfo
import com.therealityteam.videoplayer.data.source.LocalSource
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * What a local walk finds beside each video in a real folder, and what its nfo says — read only, no
 * network. `-Daura.nfo.dir=<folder>` (e.g. ~/Movies); skipped without it.
 */
class NfoProbeTest {
    @Test fun probe() = runBlocking {
        val dir = System.getProperty("aura.nfo.dir")
        assumeTrue(dir != null)
        val videos = LocalSource().list(MediaSourceConfig("probe", SourceType.LOCAL, "probe", dir!!))
        println("NFO-PROBE ${videos.size} videos, ${videos.count { it.local != null }} with something beside them")
        videos.filter { it.local != null }.sortedBy { it.path }.forEach { v ->
            val l = v.local!!
            val own = l.nfo?.let { KodiNfo.parse(KodiNfo.text(File(it).readBytes())) }
            val show = l.showNfo?.let { KodiNfo.parse(KodiNfo.text(File(it).readBytes())) }
            println("NFO-PROBE ${v.fileName}")
            println("NFO-PROBE   nfo=${l.nfo?.substringAfterLast('/')} show=${l.showNfo?.substringAfterLast('/')}")
            println("NFO-PROBE   poster=${l.poster?.substringAfterLast('/')} fanart=${l.fanart?.substringAfterLast('/')}")
            (own ?: show)?.let { n ->
                println("NFO-PROBE   ${n.kind} tmdb=${n.tmdbId} (${n.tmdbKind}) imdb=${n.imdbId} «${n.title}» / «${n.originalTitle}» ${n.year} ${n.runtime}min ${n.mpaa} ${n.genres}")
                println("NFO-PROBE   ratings=${n.ratings.joinToString { "${it.source}:${it.value}(${it.votes})" }} actors=${n.actors.size} directors=${n.directors}")
            }
        }
    }
}
