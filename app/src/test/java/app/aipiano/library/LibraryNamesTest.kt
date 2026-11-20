package app.aipiano.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryNamesTest {

    @Test
    fun cleansFileNames() {
        assertEquals("Ode an die Freude", LibraryNames.cleanFileName("Ode an die Freude.mid"))
        assertEquals("ode an die Freude", LibraryNames.cleanFileName("ode_an_die_Freude.MIDI"))
        assertEquals("stück", LibraryNames.cleanFileName("primary:Musik/stück.mid"))
        assertEquals("Mein Lied", LibraryNames.cleanFileName("/storage/emulated/0/Mein_Lied.mid"))
    }

    @Test
    fun fallsBackWhenNameIsEmpty() {
        assertEquals("MIDI-Datei", LibraryNames.cleanFileName(null))
        assertEquals("MIDI-Datei", LibraryNames.cleanFileName("   "))
        assertEquals("MIDI-Datei", LibraryNames.cleanFileName(".mid"))
    }

    @Test
    fun extractsVideoIdFromCommonYouTubeLinks() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, LibraryNames.youtubeVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, LibraryNames.youtubeVideoId("https://youtu.be/$id?si=abc"))
        assertEquals(id, LibraryNames.youtubeVideoId("https://m.youtube.com/watch?feature=share&v=$id"))
        assertEquals(id, LibraryNames.youtubeVideoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, LibraryNames.youtubeVideoId("  https://music.youtube.com/watch?v=$id  "))
    }

    @Test
    fun rejectsNonYouTubeLinks() {
        assertNull(LibraryNames.youtubeVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(LibraryNames.youtubeVideoId("https://www.youtube.com/watch?v=zu_kurz"))
        assertNull(LibraryNames.youtubeVideoId("kein link"))
        assertNull(LibraryNames.youtubeVideoId("https://www.youtube.com/"))
    }

    @Test
    fun canonicalUrlIsStable() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", LibraryNames.canonicalYouTubeUrl("dQw4w9WgXcQ"))
    }
}
