package local.chromebox.tvlauncher

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps the stack trace of the last crash in app storage, and the next start shows it. A
 * Chromebox seldom has adb at hand to read the system log, but a photo of the screen works.
 */
class LauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                crashFile(this).writeText(
                    "Chromebox TV $version · Android ${Build.VERSION.RELEASE} · ${thread.name}\n\n" +
                        error.stackTraceToString().lines().take(MAX_LINES).joinToString("\n")
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val MAX_LINES = 40

        fun crashFile(context: Context) = File(context.filesDir, "last_crash.txt")
    }
}
