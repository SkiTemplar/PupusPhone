package com.pupus.phone.guard

/** Everything the accessibility self-heal decision depends on. Built from Settings.Secure and the system. */
data class A11ySnapshot(
    /** Raw Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES (colon-separated components), may be null. */
    val enabledServices: String?,
    /** Settings.Secure.ACCESSIBILITY_ENABLED == 1. */
    val isAccessibilityEnabled: Boolean,
    /** A live service instance exists or the system lists it as bound. */
    val isRunning: Boolean,
    /** False once the user turned the service off cleanly (onUnbind): never re-add it against their will. */
    val isWanted: Boolean,
    /** WRITE_SECURE_SETTINGS granted over adb. */
    val canWriteSecureSettings: Boolean,
    val nowMs: Long,
    val lastAttemptMs: Long?,
)

enum class A11yOutcome {
    /** Bound and running: nothing to do. */
    RUNNING,

    /** Not in the enabled list and there is no reason to re-add it. */
    OFF,

    /** Enabled but dead (or missing while wanted) and WRITE_SECURE_SETTINGS is not granted. */
    NEEDS_PERMISSION,

    /** A repair would be needed but the previous attempt was too recent. */
    RATE_LIMITED,

    /** Enabled but dead: write the list without our component, then with it, to force a rebind. */
    REBIND,

    /** Missing from the list while accessibility is on and the user wanted it: add it back. */
    ENABLE,
}

/** [writes] are the successive values for ENABLED_ACCESSIBILITY_SERVICES, applied in order. */
data class A11yDecision(val outcome: A11yOutcome, val writes: List<String>) {
    val isRepair: Boolean get() = writes.isNotEmpty()
}

/**
 * Decides how to revive the accessibility service after the ROM kills the process
 * (HyperOS "SwipeUpClean"): Android then marks the service as crashed and never rebinds it.
 * Pure: no Android calls, so the setting manipulation is unit-tested.
 */
class AccessibilityRepairPolicy(
    private val packageName: String,
    private val className: String,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    /** Full flattened form, the one written back to the setting. */
    val component: String = "$packageName/$className"

    fun decide(snapshot: A11ySnapshot): A11yDecision {
        if (snapshot.isRunning) return A11yDecision(A11yOutcome.RUNNING, emptyList())
        val others = withoutOurs(snapshot.enabledServices)
        val isListed = isListed(snapshot.enabledServices)
        val outcome = when {
            isListed -> A11yOutcome.REBIND
            snapshot.isAccessibilityEnabled && snapshot.isWanted -> A11yOutcome.ENABLE
            else -> return A11yDecision(A11yOutcome.OFF, emptyList())
        }
        if (!snapshot.canWriteSecureSettings) return A11yDecision(A11yOutcome.NEEDS_PERMISSION, emptyList())
        if (isRateLimited(snapshot)) return A11yDecision(A11yOutcome.RATE_LIMITED, emptyList())
        val withOurs = join(others + component)
        val writes = if (outcome == A11yOutcome.REBIND) listOf(join(others), withOurs) else listOf(withOurs)
        return A11yDecision(outcome, writes)
    }

    fun isListed(setting: String?): Boolean = entries(setting).any(::isOurs)

    /** The other services, in their original order; our component (full or short form) removed. */
    fun withoutOurs(setting: String?): List<String> = entries(setting).filterNot(::isOurs)

    private fun isRateLimited(snapshot: A11ySnapshot): Boolean {
        val last = snapshot.lastAttemptMs ?: return false
        return snapshot.nowMs - last < minIntervalMs
    }

    private fun isOurs(entry: String): Boolean {
        val slash = entry.indexOf('/')
        if (slash <= 0) return false
        val pkg = entry.substring(0, slash)
        val cls = entry.substring(slash + 1)
        val fullClass = if (cls.startsWith(".")) pkg + cls else cls
        return pkg.equals(packageName, ignoreCase = true) && fullClass.equals(className, ignoreCase = true)
    }

    private fun entries(setting: String?): List<String> =
        setting.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun join(entries: List<String>): String = entries.joinToString(SEPARATOR.toString())

    companion object {
        const val MIN_INTERVAL_MS = 10_000L
        private const val SEPARATOR = ':'
    }
}

/** What the "Estado" card says about the accessibility service. */
enum class A11yDisplay {
    RUNNING, REPAIRED, NO_PERMISSION, OFF, DEAD;

    companion object {
        /** A repair younger than this is shown as "reparada (reconectando…)". */
        const val REPAIR_WINDOW_MS = 30_000L

        fun of(outcome: A11yOutcome, repairedAgoMs: Long?): A11yDisplay {
            val isRecentRepair = repairedAgoMs != null && repairedAgoMs < REPAIR_WINDOW_MS
            return when (outcome) {
                A11yOutcome.RUNNING -> RUNNING
                A11yOutcome.NEEDS_PERMISSION -> NO_PERMISSION
                A11yOutcome.OFF -> OFF
                A11yOutcome.RATE_LIMITED, A11yOutcome.REBIND, A11yOutcome.ENABLE -> if (isRecentRepair) REPAIRED else DEAD
            }
        }
    }
}
