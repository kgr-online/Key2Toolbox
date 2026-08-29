package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.kgr.key2toolbox.core.RootShell

/**
 * Standalone "Slim List" Recents: a vertical, thumbnail-free list of running
 * tasks, entirely independent of the launcher's own RecentsView. Unlike Grid
 * and Masonry (see [com.kgr.key2toolbox.xposed.RecentsHookInit]), this never
 * touches the launcher process - it reads live task state via root
 * `dumpsys activity recents` and switches tasks via a plain `am start`, so
 * there is nothing to hook and no launcher-crash risk.
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
     * entries; [EXCLUDED_PACKAGES] and the caller's own package are dropped as
     * a second belt-and-braces filter in case a ROM variant reports those
     * differently. Blocking (runs a root shell command) - call off the main
     * thread.
     */
    fun listTasks(context: Context): List<SlimTask> {
        val dump = RootShell.run("dumpsys activity recents").outString
        val chunks = splitIntoTaskChunks(dump)
        val pm = context.packageManager
        val selfPkg = context.packageName

        val out = ArrayList<SlimTask>(chunks.size)
        for (chunk in chunks) {
            val header = chunk.first()
            val m = TASK_HEADER.find(header) ?: continue
            val taskId = m.groupValues[1].toIntOrNull() ?: continue
            if (m.groupValues[2] in EXCLUDED_TYPES) continue

            val topComponent = topComponentOf(chunk) ?: continue
            val pkg = topComponent.substringBefore("/")
            if (pkg.isEmpty() || pkg == selfPkg || pkg in EXCLUDED_PACKAGES) continue

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
