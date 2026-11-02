package com.holdfast.ssh

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves uncaught crashes so they can be shown on next launch and sent to the PC for debugging. */
object CrashLog {
    private lateinit var dir: File

    fun install(ctx: Context) {
        dir = File(ctx.filesDir, "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { e.printStackTrace(it) }
                val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "crash-$stamp.txt").writeText(
                    "HoldFast $ver on ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                        "Thread: ${t.name}\n\n$sw"
                )
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
    }

    fun pending(): List<File> =
        if (!::dir.isInitialized) emptyList() else dir.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()

    /** Marks a report as seen in the app (it stays queued for upload to the PC). */
    fun markShown(f: File) { f.renameTo(File(f.parentFile, f.name.removeSuffix(".txt") + ".shown")) }

    fun unsent(): List<File> =
        if (!::dir.isInitialized) emptyList() else dir.listFiles()?.filter { !it.name.endsWith(".sent") }?.sortedBy { it.name } ?: emptyList()

    fun markSent(f: File) { f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".sent")) }
}
