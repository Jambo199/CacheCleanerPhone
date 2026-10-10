package io.github.jambo199.cachecleaner.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    @Test
    fun `reads the version file`() {
        assertEquals(
            UpdateManifest(107, "1.5.7"),
            UpdateManifest.parse("""{"versionCode": 107, "versionName": "1.5.7"}"""),
        )
        assertEquals(
            UpdateManifest(108, "1.5.8"),
            UpdateManifest.parse("{\n  \"versionName\" : \"1.5.8\",\n  \"versionCode\" : 108\n}\n"),
        )
    }

    @Test
    fun `falls back to the number when there's no name`() {
        assertEquals(UpdateManifest(109, "109"), UpdateManifest.parse("""{"versionCode": 109}"""))
    }

    @Test
    fun `rejects anything that isn't a version file`() {
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("<html>Not Found</html>"))
        assertNull(UpdateManifest.parse("""{"versionName": "1.5"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode": "abc"}"""))
    }

    @Test
    fun `only a higher version counts as newer`() {
        val manifest = UpdateManifest(107, "1.5.7")
        assertTrue(manifest.isNewerThan(106))
        assertFalse(manifest.isNewerThan(107))
        assertFalse(manifest.isNewerThan(200))
    }
}
