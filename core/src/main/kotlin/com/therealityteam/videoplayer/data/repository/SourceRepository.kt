package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File

/** Reactive store of configured media sources (local folders, SMB/WebDAV/DLNA…). */
interface SourceRepository {
    val sources: StateFlow<List<MediaSourceConfig>>
    fun all(): List<MediaSourceConfig>
    fun get(id: String): MediaSourceConfig?
    fun upsert(source: MediaSourceConfig)
    fun remove(id: String)
}

class JsonSourceRepository(filesDir: File) : SourceRepository {
    private val file = File(filesDir, "aura/sources.json").apply { parentFile?.mkdirs() }
    private val lock = Any()
    private val _sources = MutableStateFlow<List<MediaSourceConfig>>(emptyList())
    override val sources: StateFlow<List<MediaSourceConfig>> = _sources.asStateFlow()

    init {
        load()
    }

    private fun load() = synchronized(lock) {
        // A damaged file is set aside and the last whole one read instead (DurableFile): read as
        // "no sources", the next save would have written that over every share and its account.
        val text = com.therealityteam.videoplayer.util.DurableFile.read(file) { JSONTokener(it).nextValue() is JSONArray } ?: return
        runCatching {
            val arr = JSONTokener(text).nextValue() as? JSONArray ?: return
            val stored = (0 until arr.length()).map { MediaJson.source(arr.getJSONObject(it)) }
            _sources.value = stored.map {
                it.copy(
                    password = it.password?.let(CredentialCipher::reveal),
                    token = it.token?.let(CredentialCipher::reveal),
                )
            }
            // A file from before encryption holds credentials in the clear; rewrite it now.
            val hasPlain = stored.any { s ->
                !s.password.isNullOrEmpty() && !CredentialCipher.isProtected(s.password) ||
                    !s.token.isNullOrEmpty() && !CredentialCipher.isProtected(s.token)
            }
            if (hasPlain) persist()
        }
        Unit
    }

    private fun persist() {
        val arr = JSONArray()
        _sources.value.forEach {
            val onDisk = it.copy(
                password = it.password?.let(CredentialCipher::protect),
                token = it.token?.let(CredentialCipher::protect),
            )
            arr.put(MediaJson.source(onDisk))
        }
        com.therealityteam.videoplayer.util.DurableFile.write(file, arr.toString())
    }

    override fun all(): List<MediaSourceConfig> = _sources.value

    override fun get(id: String): MediaSourceConfig? = _sources.value.firstOrNull { it.id == id }

    override fun upsert(source: MediaSourceConfig) = synchronized(lock) {
        val list = _sources.value.toMutableList()
        val idx = list.indexOfFirst { it.id == source.id }
        if (idx >= 0) list[idx] = source else list.add(source)
        _sources.value = list
        persist()
    }

    override fun remove(id: String) = synchronized(lock) {
        _sources.value = _sources.value.filterNot { it.id == id }
        persist()
    }
}
