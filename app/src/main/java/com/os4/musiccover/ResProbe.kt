package com.os4.musiccover

import android.content.res.Resources
import android.util.TypedValue

/**
 * Diagnostic (`op resprobe --ez on true`): who asks `Resources` for resource id
 * `0xffffffff` (-1). The system logs one line per such lookup
 * (`No package ID ff found for resource ID 0xffffffff`) and cannot say who asked; the caller's
 * stack can. Read only, off unless turned on, and stops by itself after [MAX] lines.
 */
internal object ResProbe {
    @Volatile private var on = false
    @Volatile private var left = 0
    private val seen = HashSet<String>()

    private const val MAX = 60

    @JvmStatic fun setOn(value: Boolean) {
        on = value
        if (value) {
            synchronized(seen) { seen.clear() }
            left = MAX
        }
        Xp.log("MCRes: probe " + (if (value) "on, up to $MAX callers" else "off"))
    }

    fun install(classLoader: ClassLoader) {
        val cls = runCatching { Class.forName("android.content.res.Resources", false, classLoader) }
            .getOrElse {
                Xp.w("MCRes: Resources unavailable: $it")
                return
            }
        for (name in listOf(
            "getValue", "getResourceName", "getResourceText", "getResourceEntryName",
            "getDrawable", "getDrawableForDensity", "getText", "getString", "openRawResource",
        )) {
            runCatching {
                Xp.hookAll(cls, name) { chain ->
                    if (on && left > 0) {
                        val id = chain.args.firstOrNull { it is Int } as? Int
                        if (id == -1) note(name)
                    }
                    chain.proceed()
                }
            }.onFailure { Xp.w("MCRes: ${name} unavailable: $it") }
        }
    }

    private fun note(name: String) {
        if (left <= 0) return
        val stack = Throwable().stackTrace
            .drop(3)
            .filterNot { f ->
                val n = f.className
                n.startsWith("java.") || n.startsWith("android.content.res.") ||
                    n.startsWith("android.os.") || n.startsWith("de.robv.") ||
                    n.startsWith("org.lsposed.") || n.startsWith("kotlin.") ||
                    n == "com.os4.musiccover.ResProbe"
            }
            .take(10)
            .joinToString(" <- ") { it.className + "." + it.methodName }
        synchronized(seen) {
            if (!seen.add(stack)) return
            if (left <= 0) return
            left--
        }
        Xp.log("MCRes: id=0xffffffff via $name from: $stack")
    }
}
