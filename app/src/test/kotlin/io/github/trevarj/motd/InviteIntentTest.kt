package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.JoinInviteV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InviteIntentTest {
    @Test
    fun `cold or warm view intent yields normalized payload`() {
        val invite = JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(JoinInviteCodec.appUri(invite)))

        assertEquals(JoinInviteCodec.encode(invite), parseJoinInvitePayload(intent))
    }

    /** Camera and photo-library access remain runtime-granted optional capabilities. */
    @Test
    fun `manifest exposes browsable invite and optional media permissions`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolved = context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("motd://invite?v=x")), PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull(resolved)
        val requested =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        assertTrue(Manifest.permission.CAMERA in requested)
        assertTrue(Manifest.permission.READ_MEDIA_IMAGES in requested)
        assertTrue(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in requested)
    }

    @Test
    fun `matching invalid link is retained as safe error while unrelated intent is ignored`() {
        assertEquals("", parseJoinInvitePayload(Intent(Intent.ACTION_VIEW, Uri.parse("motd://invite?v=bad"))))
        assertNull(parseJoinInvitePayload(Intent(Intent.ACTION_MAIN)))
    }
}
