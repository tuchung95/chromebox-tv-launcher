package local.chromebox.tvlauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One app listed in the update manifest, compared with what is installed. */
data class AppUpdate(
    val pkg: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val installedVersion: Long?
) {
    val isInstalled get() = installedVersion != null
    val hasUpdate get() = installedVersion == null || versionCode > installedVersion
}

/**
 * In-app updates. Each GitHub release carries `updates.json` (see scripts/release.sh):
 * `{"apps":[{"package":"…","name":"…","versionCode":2,"versionName":"1.0.2","apk":"https://…apk","sha256":"…"}]}`
 * A relative `apk` path is resolved against the manifest address, so any web server works too.
 *
 * Downloads are checked against the SHA-256 and the expected package and version before
 * they are handed to the system installer. Android still asks the viewer to confirm each
 * install, because side-loaded apps cannot update silently.
 *
 * [fetchApk] and [install] also install SmartTube when the viewer asks for it.
 *
 * Every method except [canInstall] and [installPermissionIntent] blocks; call them off the
 * main thread.
 */
class UpdateManager(private val context: Context) {

    fun check(manifestUrl: String): List<AppUpdate> {
        val json = JSONObject(String(fetch(manifestUrl), Charsets.UTF_8))
        val apps = json.getJSONArray("apps")
        val base = URL(manifestUrl)
        return List(apps.length()) { i ->
            val o = apps.getJSONObject(i)
            val pkg = o.getString("package")
            AppUpdate(
                pkg = pkg,
                name = o.optString("name", pkg),
                versionCode = o.getLong("versionCode"),
                versionName = o.optString("versionName", o.getLong("versionCode").toString()),
                apkUrl = URL(base, o.getString("apk")).toString(),
                sha256 = o.getString("sha256").lowercase(),
                installedVersion = installedVersion(pkg)
            )
        }
    }

    fun installedVersion(pkg: String): Long? = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Downloads and verifies the APK; [onProgress] receives 0–100. */
    fun download(update: AppUpdate, onProgress: (Int) -> Unit): File {
        val target = fetchApk(update.apkUrl, update.sha256, "${update.pkg}-${update.versionCode}.apk", onProgress)
        val archive = context.packageManager.getPackageArchiveInfo(target.path, 0)
        val archiveVersion = archive?.let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        if (archive?.packageName != update.pkg || archiveVersion != update.versionCode) {
            target.delete()
            throw IOException(context.getString(R.string.update_bad_package))
        }
        return target
    }

    /** Package name inside a downloaded APK, or null when it can't be read. */
    fun packageOf(apk: File): String? = context.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName

    fun fetchText(url: String): String = String(fetch(url), Charsets.UTF_8)

    /** Downloads an APK into the cache as [name] and checks its SHA-256; [onProgress] receives 0–100. */
    fun fetchApk(url: String, sha256: String, name: String, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, name)
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != sha256.lowercase()) {
            target.delete()
            throw IOException(context.getString(R.string.update_bad_hash))
        }
        return target
    }

    /** Hands the APK to the system installer, which shows its confirmation dialog. */
    fun install(apk: File, pkg: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .putExtra(InstallResultReceiver.EXTRA_PACKAGE, pkg)
            // The installer fills in the status extras, so the intent must stay mutable
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    private fun fetch(url: String): ByteArray {
        val connection = open(url)
        try {
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 20000
        connection.useCaches = false
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("HTTP $code")
        }
        return connection
    }
}

/** Reopens the launcher after it has updated itself, since the update ends its process. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Receives the system installer's result for each update session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The launcher's own update, or another app it installed such as SmartTube
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                val message = if (pkg == null || pkg == context.packageName) R.string.update_installed else R.string.install_done
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Toast.makeText(context, R.string.update_cancelled, Toast.LENGTH_SHORT).show()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.update_failed, message), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
