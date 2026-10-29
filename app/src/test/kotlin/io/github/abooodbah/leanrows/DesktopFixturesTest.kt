package io.github.abooodbah.leanrows

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopFixturesTest {
    @Test fun portWritesTheSameBytesAsTheDesktopScript() {
        for ((name, sha256) in DesktopFixtures.SHA256) {
            assertEquals(name, sha256, DesktopFixtures.sha256(name))
        }
    }
}
