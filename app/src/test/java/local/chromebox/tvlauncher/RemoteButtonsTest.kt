package local.chromebox.tvlauncher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteButtonsTest {

    @Test
    fun actionsSurviveSaving() {
        val actions = listOf(
            ButtonAction.Voice, ButtonAction.Home, ButtonAction.AllApps, ButtonAction.Fullscreen, ButtonAction.Ignore,
            ButtonAction.OpenApp("org.videolan.vlc"), ButtonAction.OpenWeb("1234-abcd")
        )
        actions.forEach { assertEquals(it, ButtonAction.parse(it.value)) }
    }

    @Test
    fun unknownValuesAreDropped() {
        assertNull(ButtonAction.parse(""))
        assertNull(ButtonAction.parse("app:"))
        assertNull(ButtonAction.parse("launch-rocket"))
    }

    @Test
    fun navigationAndTypingStayFree() {
        assertFalse(RemoteButtons.canAssign(KeyEvent.KEYCODE_DPAD_CENTER, printing = false))
        assertFalse(RemoteButtons.canAssign(KeyEvent.KEYCODE_BACK, printing = false))
        assertFalse(RemoteButtons.canAssign(KeyEvent.KEYCODE_VOLUME_UP, printing = false))
        assertFalse(RemoteButtons.canAssign(KeyEvent.KEYCODE_A, printing = true))
    }

    @Test
    fun remoteExtrasCanBeAssigned() {
        assertTrue(RemoteButtons.canAssign(KeyEvent.KEYCODE_MENU, printing = false))
        assertTrue(RemoteButtons.canAssign(KeyEvent.KEYCODE_SEARCH, printing = false))
        assertTrue(RemoteButtons.canAssign(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, printing = false))
        assertTrue(RemoteButtons.canAssign(KeyEvent.KEYCODE_F5, printing = false))
    }
}
