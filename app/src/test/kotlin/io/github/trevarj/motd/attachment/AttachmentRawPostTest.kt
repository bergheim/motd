package io.github.trevarj.motd.attachment

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentRawPostTest {
    @Test
    fun basicAuthHeaderRequiresUsername() {
        assertEquals(null, basicAuthHeader("", "secret"))
        assertEquals("Basic dXNlcjpzZWNyZXQ=", basicAuthHeader("user", "secret"))
    }

    @Test
    fun customRedirectAcceptsOnlyUploadResultOrIncorrect() {
        assertEquals(true, isCustomUploadRedirect("https://share.example/upload", "/upload/quick-fox"))
        assertEquals(true, isCustomUploadRedirect("https://share.example/upload", "/incorrect"))
        assertEquals(false, isCustomUploadRedirect("https://share.example/upload", "/login"))
        assertEquals(false, isCustomUploadRedirect("https://share.example/upload", "https://other.example/upload/quick-fox"))
        assertEquals(false, isCustomUploadRedirect("https://share.example/upload", null))
    }

    @Test
    fun customResultUrlMapsUploadPathToFile() {
        assertEquals(
            "https://share.example/file/quick-fox",
            customResultUrl("https://share.example/upload", "https://share.example/upload/quick-fox"),
        )
    }

    @Test
    fun customResultUrlResolvesRelativeLocation() {
        assertEquals(
            "https://share.example/file/quick-fox",
            customResultUrl("https://share.example/upload", "/upload/quick-fox"),
        )
    }

    @Test
    fun customResultUrlUsesHttpsLocationWhenNotAnUploadPage() {
        assertEquals(
            "https://cdn.example/p.jpg",
            customResultUrl("https://share.example/upload", "https://cdn.example/p.jpg"),
        )
    }

    @Test
    fun customResultUrlFallsBackToBody() {
        assertEquals(
            "https://cdn.example/photo.jpg",
            customResultUrl("https://share.example/upload", null, "https://cdn.example/photo.jpg"),
        )
    }

    @Test(expected = UploadException::class)
    fun customResultUrlRejectsIncorrectPasswordRedirect() {
        customResultUrl("https://share.example/upload", "https://share.example/incorrect")
    }
}
