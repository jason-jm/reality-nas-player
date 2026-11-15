package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.FrameCrop
import com.therealityteam.videoplayer.data.repository.CropStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class CropStoreTest {

    @Test fun `what was found is read back — bars, and no bars`() {
        val dir = Files.createTempDirectory("crops").toFile()
        val store = CropStore(dir)
        assertNull(store.of("lotr"))
        store.put("lotr", FrameCrop(0f, 0.128f, 0f, 0.128f))
        store.put("mando", FrameCrop.NONE)
        assertEquals(FrameCrop(0f, 0.128f, 0f, 0.128f), store.of("lotr"))
        assertEquals(FrameCrop.NONE, store.of("mando"))
        // Round trip through the file's own form.
        val text = CropStore.encode(mapOf("lotr" to FrameCrop(0f, 0.128f, 0f, 0.128f), "mando" to FrameCrop.NONE))
        val back = CropStore.decode(text)
        assertEquals(0.128f, back.getValue("lotr").top, 0.0001f)
        assertEquals(FrameCrop.NONE, back.getValue("mando"))
        store.forget("lotr")
        assertNull(store.of("lotr"))
    }
}
