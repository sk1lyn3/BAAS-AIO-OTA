package moe.baas.aio

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Android side of downloaded BAAS cores: network, APK facts, activation by process restart. */
object CoreUpdater {
    private const val MAX_INDEX = 1L shl 20

    fun root(context: Context) = File(context.filesDir, "core")
    private fun keys(context: Context) =
        CoreBundle.publicKeys(context.resources.openRawResource(R.raw.core_bundle_keys).bufferedReader().use { it.readText() })
    private fun versionCode(context: Context): Long {
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }
    fun bundled(context: Context): JSONObject =
        context.assets.open("core-bundled.json").bufferedReader().use { JSONObject(it.readText()) }

    private fun preferences(context: Context) = context.getSharedPreferences("core_update", Context.MODE_PRIVATE)
    fun mirror(context: Context) = preferences(context).getString("mirror", "").orEmpty()
    fun setMirror(context: Context, value: String) {
        val prefix = value.trim()
        require(prefix.isEmpty() || prefix.startsWith("https://") || prefix.startsWith("http://")) { "镜像地址需要以 https:// 开头" }
        preferences(context).edit().putString("mirror", prefix).apply()
    }
    /** Mirrors only relay bytes: the index and the package are both signed, so a mirror cannot alter them. */
    private fun through(context: Context, url: String) = mirror(context).let { if (it.isEmpty()) url else it.trimEnd('/') + "/" + url }

    val idle get() = !TaskRuntime.isActive && !TaskRuntime.isProbeRunning && !ManagedMode.executionActive

    /** Called once per process before Kotlin reads generated core data. */
    fun reconcile(context: Context) {
        CoreBundle.reconcile(root(context), BuildConfig.CORE_API, bundled(context), versionCode(context))
    }

    data class Status(val commit: String, val commitTime: String, val external: Boolean, val canRollBack: Boolean, val pending: Boolean, val error: String?)

    fun status(context: Context): Status {
        val root = root(context); val state = CoreBundle.readState(root)
        val id = CoreBundle.effective(root); val manifest = CoreBundle.manifest(root, id)
        val core = manifest?.getJSONObject("core")
        val bundled = bundled(context)
        val previous = CoreBundle.id(state, "previous")
        return Status(
            core?.getString("commit") ?: bundled.getString("head"),
            core?.getString("commitTime") ?: bundled.getString("commitTime"),
            manifest != null,
            previous != null && previous != id && previous != (CoreBundle.id(state, "active") ?: CoreBundle.BUNDLED),
            CoreBundle.id(state, "pending") != null,
            CoreBundle.id(state, "lastError"))
    }

    private fun open(context: Context, url: String): HttpURLConnection =
        (URL(through(context, url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BAAS-AIO/${BuildConfig.VERSION_NAME}")
            if (responseCode !in 200..299) { disconnect(); throw CoreBundle.Rejected("下载失败：HTTP $responseCode") }
        }

    private fun fetch(context: Context, url: String): ByteArray = open(context, url).run {
        try {
            inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); if (out.size() > MAX_INDEX) throw CoreBundle.Rejected("更新信息过大") }
                out.toByteArray()
            }
        } finally { disconnect() }
    }

    /** Network call; returns a package newer than the core this APK would run, or null. */
    fun check(context: Context, channelUrl: String = BuildConfig.CORE_CHANNEL_URL): CoreBundle.Offer? {
        val body = fetch(context, channelUrl); val signature = fetch(context, "$channelUrl.sig")
        val offer = CoreBundle.offer(body, signature, keys(context), BuildConfig.CORE_API, versionCode(context)) ?: return null
        val current = status(context); val bundled = bundled(context)
        return offer.takeIf {
            CoreBundle.newer(it.commit, it.commitTime, current.commit, current.commitTime) &&
                CoreBundle.newer(it.commit, it.commitTime, bundled.getString("head"), bundled.getString("commitTime"))
        }
    }

    class Cancelled : Exception()

    /** [cancelled] is polled between chunks; a cancelled download leaves nothing behind. */
    fun download(context: Context, offer: CoreBundle.Offer, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val directory = File(root(context), "downloads").apply { deleteRecursively(); mkdirs() }
        val partial = File(directory, "core-${offer.commit.take(12)}.zip.partial")
        open(context, offer.url).run {
            try {
                inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(1 shl 16); var done = 0L
                        while (true) {
                            if (cancelled()) throw Cancelled()
                            val n = input.read(buffer); if (n < 0) break
                            done += n; if (done > offer.size) throw CoreBundle.Rejected("下载的文件大小不对")
                            output.write(buffer, 0, n); progress(done, offer.size)
                        }
                        output.fd.sync()
                    }
                }
            } catch (error: Exception) {
                directory.deleteRecursively(); throw error
            } finally { disconnect() }
        }
        return File(directory, "core-${offer.commit.take(12)}.zip").also { check(partial.renameTo(it)) { "无法保存下载" } }
    }

    fun install(context: Context, offer: CoreBundle.Offer, zip: File): String {
        check(idle) { "请等待当前任务结束" }
        try {
            return CoreBundle.install(zip, offer, root(context), keys(context), BuildConfig.CORE_API, versionCode(context),
                bundled(context), CoreBundle::checkData)
        } finally { zip.parentFile?.deleteRecursively() }
    }

    fun rollback(context: Context) { check(idle) { "请等待当前任务结束" }; CoreBundle.rollback(root(context)) }
    fun restoreBundled(context: Context) { check(idle) { "请等待当前任务结束" }; CoreBundle.restoreBundled(root(context)) }

    /**
     * A pending core is self-tested by core_loader on the first Python start of a process. Run that start now,
     * off the UI thread, so a broken package is rejected before the user starts a task. [done] gets true when
     * the pending core became active.
     */
    fun testPending(context: Context, done: (Boolean, String?) -> Unit) {
        val app = context.applicationContext
        if (!status(app).pending || !idle) return
        kotlin.concurrent.thread(name = "baas-core-check") {
            val passed = try {
                BundledAssets.sync(app, "baas-source.zip")
                if (!Python.isStarted()) Python.start(AndroidPlatform(app))
                Python.getInstance().getModule("core_loader").callAttr("prepare", app.filesDir.absolutePath)
                !status(app).pending && status(app).error == null
            } catch (_: Throwable) { false }
            val error = status(app).error
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(passed, error) }
        }
    }

    /** Python and Kotlin both lock their core choice per process, so a change applies in a fresh process. */
    fun restart(activity: Activity) {
        val intent = Intent(activity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(activity, 7301, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        val at = System.currentTimeMillis() + 800
        activity.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, pending), pending)
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
