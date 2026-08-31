package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Base64
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.kgr.key2toolbox.core.RootShell

/**
 * Standalone Recents for the two overlay modes - "Slim List" (thumbnail-free
 * vertical list) and "Masonry" (the same list with each row carrying its app's
 * last snapshot and a staggered height). Both are entirely independent of the
 * launcher's own RecentsView - unlike Grid (see
 * [com.kgr.key2toolbox.xposed.RecentsHookInit]) - reading live task state via
 * root `dumpsys activity recents`, snapshots straight off the system snapshot
 * cache, and switching tasks via a plain `am start`. Nothing to hook, no
 * launcher-crash risk.
 *
 * Resume-not-restart: verified on-device that `am start -n <component>
 * -f 0x00020000` (Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) brings an existing
 * task to front in place rather than recreating its top activity, so long as
 * <component> is the task's *current* top activity - not necessarily the
 * task's root/launch intent, which `dumpsys`'s own `mActivityComponent=` /
 * `intent=` fields describe instead. The top activity is the last entry in
 * that task's `Activities=[...]` line.
 */
object SlimRecentsController {

    data class SlimTask(
        val taskId: Int,
        val packageName: String,
        /** The task's current top activity, e.g. "com.foo.bar/.MainActivity" - what gets passed to `am start -n`. */
        val topComponent: String,
        val label: String,
        val icon: Drawable?,
    )

    private val TASK_HEADER = Regex("""Task\{[0-9a-f]+ #(\d+) type=(\S+)""")
    private val ACTIVITY_RECORD = Regex("""ActivityRecord\{\S+ u\d+ ([\w.]+/[\w.${'$'}]+) t\d+\}""")

    private val EXCLUDED_PACKAGES = setOf(
        "com.android.launcher3",
        "org.lineageos.trebuchet",
    )

    /**
     * `type=` values seen in real dumps on this build: `standard` for a task
     * that's been the foreground task this boot session, `undefined` for a
     * perfectly normal app task that's just sitting cold since before the
     * last boot (no live Activities, restored from the persisted task list -
     * this is also why resuming one of these necessarily launches fresh
     * rather than resuming: there's no process behind it to reorder to
     * front). Only `home`/`recents` are the launcher's own non-app entries
     * and worth excluding by type; everything else - `standard` and
     * `undefined` alike - is a real switchable app task.
     */
    private val EXCLUDED_TYPES = setOf("home", "recents")

    /**
     * Live, ordered (most-recent-first, matching the dump's own order) list of
     * switchable tasks. `type=standard` already excludes home/recents/assistant
     * entries; [EXCLUDED_PACKAGES] is dropped as a second belt-and-braces filter
     * in case a ROM variant reports those differently. K2TB's own task is
     * intentionally included - Slim List is a standalone overlay that can be
     * triggered while any app (including K2TB itself) is foreground, so K2TB
     * is just as switchable-to as anything else. Blocking (runs a root shell
     * command) - call off the main thread.
     */
    fun listTasks(context: Context): List<SlimTask> {
        val dump = RootShell.run("dumpsys activity recents").outString
        val chunks = splitIntoTaskChunks(dump)
        val pm = context.packageManager

        val out = ArrayList<SlimTask>(chunks.size)
        for (chunk in chunks) {
            val header = chunk.first()
            val m = TASK_HEADER.find(header) ?: continue
            val taskId = m.groupValues[1].toIntOrNull() ?: continue
            if (m.groupValues[2] in EXCLUDED_TYPES) continue

            val topComponent = topComponentOf(chunk) ?: continue
            val pkg = topComponent.substringBefore("/")
            if (pkg.isEmpty() || pkg in EXCLUDED_PACKAGES) continue

            val (label, icon) = labelAndIcon(pm, pkg)
            out.add(SlimTask(taskId, pkg, topComponent, label, icon))
        }
        return out
    }

    /**
     * Bring [task] to front without recreating it - see class doc for why
     * this specific flag. Blocking - call off the main thread.
     */
    fun resumeTask(task: SlimTask) {
        RootShell.run("am start -n ${task.topComponent} -f 0x00020000")
    }

    /**
     * Removes [task] from the recents list outright - not just killing its
     * process. Confirmed on-device: `am force-stop` only kills the process
     * and leaves the task record in `dumpsys activity recents` untouched
     * (proven by the dump itself: several real tasks sit there with zero live
     * process at all). `am stack remove <taskId>` actually deletes the task
     * record, verified by diffing the dump before/after. Also better than
     * force-stop alone for a package with multiple simultaneous tasks: this
     * targets one task by ID rather than closing all of that package's tasks
     * at once. Chains a force-stop afterward too - unconfirmed whether
     * `stack remove` alone also kills the process or could leave it as a
     * background orphan; the force-stop is a no-op if the process already
     * exited on its own. Blocking - call off the main thread.
     */
    fun dismissTask(task: SlimTask) {
        RootShell.run("am stack remove ${task.taskId} ; am force-stop ${task.packageName}")
    }

    /** Removes every listed task in one shell round-trip. */
    fun dismissAll(tasks: List<SlimTask>) {
        if (tasks.isEmpty()) return
        val cmd = tasks.joinToString(" ; ") { "am stack remove ${it.taskId} ; am force-stop ${it.packageName}" }
        RootShell.run(cmd)
    }

    // --- snapshots + tile spans (Masonry mode) --------------------

    /**
     * Column span (= a square tile's side, in grid cells) for a tile in the
     * 3-wide quilt: the most recent task ([indexInList] 0) is a 3x3 hero;
     * roughly a third of the rest are 2x2, the others 1x1.
     */
    fun tileSpan(indexInList: Int, taskId: Int): Int = when {
        indexInList == 0 -> 3
        (taskId % 3) == 0 -> 2
        else -> 1
    }

    /**
     * The system task-snapshot cache: `/data/system_ce/<user>/snapshots/`, with
     * `<taskId>.jpg` (full) + `<taskId>_reduced.jpg` (~half res) + a tiny
     * `<taskId>.proto`. These are the exact images the launcher's own Overview
     * renders from - captured when a task is backgrounded. Root-readable
     * (`0600 system:system`, `system_data_file`); confirmed on the Key2's LOS
     * build. `taskId` matches `dumpsys activity recents` 1:1.
     */
    private fun snapshotDir(): String = "/data/system_ce/${Process.myUid() / 100000}/snapshots"

    /**
     * Session cache of decoded snapshots, keyed by "taskId:mtime" so a fresh
     * capture invalidates the old bitmap. Recents is opened over and over, so
     * this makes the second+ open of an unchanged task instant.
     */
    private val snapshotCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 40
    }

    /**
     * Load each task's reduced snapshot, half-decoded ([inSampleSize] 2 - the
     * cards are small) and cached per id+mtime.
     *
     * Two root steps: a single `stat` for every file's mtime (~50 ms), then
     * `base64` ONLY for the ids not already in [snapshotCache] at that mtime.
     * The common case - reopening Recents with nothing changed - skips the
     * expensive base64 entirely and returns from cache. Blocking - call off the
     * main thread.
     */
    fun loadSnapshots(taskIds: List<Int>): Map<Int, Bitmap> {
        if (taskIds.isEmpty()) return emptyMap()
        val dir = snapshotDir()
        val paths = taskIds.joinToString(" ") { "'$dir/${it}_reduced.jpg'" }

        // id -> mtime, from one stat call.
        val mtimes = HashMap<Int, String>()
        RootShell.run("stat -c '%Y %n' $paths 2>/dev/null").out.forEach { line ->
            val sp = line.indexOf(' '); if (sp < 0) return@forEach
            val id = line.substringAfterLast('/').removeSuffix("_reduced.jpg").toIntOrNull() ?: return@forEach
            mtimes[id] = line.substring(0, sp)
        }

        val result = LinkedHashMap<Int, Bitmap>()
        val misses = ArrayList<Int>()
        for (id in taskIds) {
            val mt = mtimes[id] ?: continue
            val cached = snapshotCache["$id:$mt"]
            if (cached != null) result[id] = cached else misses.add(id)
        }
        android.util.Log.d("Key2Toolbox", "loadSnapshots: ${taskIds.size} ids, ${mtimes.size} have files, ${result.size} cached, ${misses.size} to fetch")
        if (misses.isEmpty()) return result

        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        // `base64 -w0` emits no trailing newline - the explicit `echo` after it
        // is the separator, otherwise the next `@id` marker glues onto the blob.
        val script = misses.joinToString("\n") { id ->
            "echo '@$id'; base64 -w0 '$dir/${id}_reduced.jpg' 2>/dev/null; echo"
        }
        val lines = RootShell.run(script).out
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("@")) { i++; continue }
            val id = line.substring(1).toIntOrNull()
            val b64 = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("@") }
            i += if (b64 != null) 2 else 1
            if (id == null || b64 == null) continue
            runCatching {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()?.let {
                result[id] = it
                mtimes[id]?.let { mt -> snapshotCache["$id:$mt"] = it }
            }
        }
        return result
    }

    /**
     * A live screenshot of the current screen, for the "hero" tile - the
     * foreground app has no fresh stored snapshot (those are taken on
     * background) so its stored tile is stale or black. Root `screencap`
     * (the AccessibilityService `takeScreenshot` API needs a capability this
     * service doesn't hold, and `adb`-level screencap is blocked on this ROM;
     * root works). [cropTop] / [cropBottom] px trim the status bar and, if it's
     * up, the toolbelt strip. Half-decoded. `null` on any failure. Blocking.
     */
    fun captureScreen(cropTop: Int, cropBottom: Int): Bitmap? = runCatching {
        val b64 = RootShell.run("screencap -p 2>/dev/null | base64 -w0").outString.trim()
        if (b64.isEmpty()) return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return null
        val t = (cropTop / 2).coerceIn(0, full.height - 1)
        val b = (cropBottom / 2).coerceIn(0, full.height - 1 - t)
        if (t == 0 && b == 0) full
        else Bitmap.createBitmap(full, 0, t, full.width, (full.height - t - b).coerceAtLeast(1))
    }.getOrNull()

    // --- per-app banner colour ------------------------------------

    private val bannerColorCache = HashMap<String, Int>()
    private val DEFAULT_BANNER = Color.rgb(38, 38, 38)

    /**
     * A muted, desaturated colour drawn from the app's icon - the card's
     * name-strip background. Same approach as the Ticker's app-icon colour mode
     * ([TickerColorResolver]): Palette dominant swatch, saturation capped, held
     * in a dark-but-not-black lightness band so white text stays readable.
     * Cached per package. Uses the already-loaded [SlimTask.icon] so it's a
     * ~3 ms Palette pass, cheap enough to call while building the row.
     */
    fun bannerColor(packageName: String, icon: Drawable?): Int =
        bannerColorCache.getOrPut(packageName) {
            runCatching {
                val bmp = (icon ?: return@runCatching DEFAULT_BANNER).toBitmap(width = 48, height = 48)
                val rgb = Palette.from(bmp).generate().let {
                    it.dominantSwatch?.rgb ?: it.vibrantSwatch?.rgb ?: it.mutedSwatch?.rgb
                } ?: return@runCatching DEFAULT_BANNER
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(rgb, hsl)
                hsl[1] = hsl[1].coerceAtMost(0.38f)
                hsl[2] = hsl[2].coerceIn(0.20f, 0.34f)
                ColorUtils.HSLToColor(hsl)
            }.getOrDefault(DEFAULT_BANNER)
        }

    // --- parsing ------------------------------------------------------

    private fun splitIntoTaskChunks(dump: String): List<List<String>> {
        val chunks = mutableListOf<MutableList<String>>()
        for (line in dump.lineSequence()) {
            if (line.trimStart().startsWith("* Recent #")) {
                chunks.add(mutableListOf(line))
            } else if (chunks.isNotEmpty()) {
                chunks.last().add(line)
            }
        }
        return chunks
    }

    /** The task's current top activity: last entry in `Activities=[...]`, falling back to `mActivityComponent=`. */
    private fun topComponentOf(chunk: List<String>): String? {
        val activitiesLine = chunk.firstOrNull { it.trimStart().startsWith("Activities=[") }
        val fromActivities = activitiesLine
            ?.let { line -> ACTIVITY_RECORD.findAll(line).map { it.groupValues[1] }.lastOrNull() }
        if (fromActivities != null) return fromActivities

        return chunk.firstOrNull { it.trimStart().startsWith("mActivityComponent=") }
            ?.substringAfter("mActivityComponent=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun labelAndIcon(pm: PackageManager, pkg: String): Pair<String, Drawable?> = runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai)
    }.getOrElse { pkg to null }
}
