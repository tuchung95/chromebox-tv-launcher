package local.chromebox.tvlauncher

import android.view.KeyEvent

/** What a remote button does while the launcher or its web player is in front. */
sealed interface ButtonAction {
    /** Stored form, for example "voice" or "app:org.videolan.vlc". */
    val value: String

    /** Opens the remote's microphone for a voice command, as the mic button does. */
    data object Voice : ButtonAction {
        override val value = "voice"
    }

    data object Home : ButtonAction {
        override val value = "home"
    }

    data object AllApps : ButtonAction {
        override val value = "apps"
    }

    data object Fullscreen : ButtonAction {
        override val value = "fullscreen"
    }

    /** The launcher's Search tab. */
    data object Search : ButtonAction {
        override val value = "search"
    }

    /** Swallows the button, for keys that do something unwanted. */
    data object Ignore : ButtonAction {
        override val value = "none"
    }

    data class OpenApp(val pkg: String) : ButtonAction {
        override val value get() = APP + pkg
    }

    data class OpenWeb(val id: String) : ButtonAction {
        override val value get() = WEB + id
    }

    companion object {
        private const val APP = "app:"
        private const val WEB = "web:"
        private const val RETIRED_SMARTTUBE_SEARCH = "smarttube_search"

        fun parse(value: String): ButtonAction? = when {
            value == Voice.value -> Voice
            value == Home.value -> Home
            value == AllApps.value -> AllApps
            value == Fullscreen.value -> Fullscreen
            value == Search.value -> Search
            // Versions 1.0.16 and older could open SmartTube's search
            value == RETIRED_SMARTTUBE_SEARCH -> Search
            value == Ignore.value -> Ignore
            value.startsWith(APP) && value.length > APP.length -> OpenApp(value.removePrefix(APP))
            value.startsWith(WEB) && value.length > WEB.length -> OpenWeb(value.removePrefix(WEB))
            else -> null
        }
    }
}

/** Which remote buttons can be assigned, and what to call them. */
object RemoteButtons {

    /**
     * Keys the launcher needs for moving around, typing and volume. ChromeOS keeps some other
     * buttons for itself (Home, the Assistant, power), and those never reach the launcher.
     */
    private val RESERVED = setOf(
        KeyEvent.KEYCODE_UNKNOWN,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM
    )

    /** [printing] is true for letters, digits and other keys that type a character. */
    fun canAssign(keyCode: Int, printing: Boolean): Boolean = !printing && keyCode !in RESERVED

    /** Names of common remote buttons, as string resources. Other keys use Android's name. */
    val NAMES: Map<Int, Int> = mapOf(
        // Xiaomi voice remotes send F5 for the mic button when no app uses their voice service
        KeyEvent.KEYCODE_F5 to R.string.key_f5,
        KeyEvent.KEYCODE_MENU to R.string.key_menu,
        KeyEvent.KEYCODE_SEARCH to R.string.key_search,
        KeyEvent.KEYCODE_VOICE_ASSIST to R.string.key_voice,
        KeyEvent.KEYCODE_ASSIST to R.string.key_voice,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to R.string.key_play_pause,
        KeyEvent.KEYCODE_MEDIA_PLAY to R.string.key_play,
        KeyEvent.KEYCODE_MEDIA_PAUSE to R.string.key_pause,
        KeyEvent.KEYCODE_MEDIA_STOP to R.string.key_stop,
        KeyEvent.KEYCODE_MEDIA_NEXT to R.string.key_next,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to R.string.key_previous,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to R.string.key_forward,
        KeyEvent.KEYCODE_MEDIA_REWIND to R.string.key_rewind,
        KeyEvent.KEYCODE_SETTINGS to R.string.key_settings,
        KeyEvent.KEYCODE_GUIDE to R.string.key_guide,
        KeyEvent.KEYCODE_INFO to R.string.key_info,
        KeyEvent.KEYCODE_TV_INPUT to R.string.key_input,
        KeyEvent.KEYCODE_CHANNEL_UP to R.string.key_channel_up,
        KeyEvent.KEYCODE_CHANNEL_DOWN to R.string.key_channel_down
    )
}
