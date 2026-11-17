package com.os4.musiccover

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The keyguard stack's "N notifications" state (KeyguardNotificationState.NUMBER) as the stack
 * island's folded form - ColorOS's way (read 2026-09-30): the ordinary notifications' rows never
 * leave the stack; folded, the stack itself hides them (numStateAlpha 0, piled at its bottom) and
 * the island stands where its "N个通知" line was; opened, the stack scrolls them out as its list.
 *
 * Everything here is SystemUI's own path, read from this build (md5 4ddaa4a0): the state is
 * where the stack is scrolled to, and going to one is scrolling there, as the full-screen AOD
 * does it (NotificationContainerViewBinder lambda 16). See keyguard-notification-fold.
 */
internal object NumState {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val artworkScrollStates = java.util.WeakHashMap<Any, ArtworkScrollState>()

    /** The islands fold the ordinary notifications this way (LockIslands.setActive turns it on). */
    @Volatile var folding = false
        private set

    /** Whether the hooks went in: without them nothing here is used, and the old way stays. */
    @Volatile var available = false
        private set

    /** The stack as it last said, from its own enter/exitNumState; null until it has. */
    @Volatile var inNumber: Boolean? = null
        private set

    private var modelRef: WeakReference<Any>? = null
    private var countRef: WeakReference<View>? = null

    /** For `op mini` / `op numstate`: the last few things done here. */
    private val log = ArrayDeque<String>()

    fun trace(what: String) {
        synchronized(log) {
            log.addLast("${SystemClock.uptimeMillis() % 100000} $what")
            while (log.size > 16) log.removeFirst()
        }
    }

    fun describe(): String = "numstate folding=$folding available=$available in=$inNumber " +
        LockIslands.systemHiddenForProbe().let { if (it.isEmpty()) "" else "sysHidden=[$it] " } +
        "default=${runCatching { defaultFlow()?.let { Xp.callMethod(it, "getValue") } }.getOrNull()} " +
        "log=" + synchronized(log) { log.joinToString(" ; ") } +
        " || artwork=[${LockIslands.notificationArtworkSampling()}]" +
        " || frames=" + framesText()

    fun install(classLoader: ClassLoader) {
        val vmClass = runCatching {
            Xp.findClass("com.miui.systemui.notification.view.viewmodel.NotificationContainerViewModel", classLoader)
        }.getOrElse {
            Xp.log("MCNum: no container model, ordinary notifications stay islands the old way: $it")
            return
        }
        runCatching {
            glassClear = Xp.findClass("com.miui.systemui.util.MiGlassCompat", classLoader)
                .getDeclaredMethod("setMiGlassCompat", android.view.View::class.java, FloatArray::class.java)
                .apply { isAccessible = true }
        }
        runCatching {
            for ((name, value) in listOf("enterNumState" to true, "exitNumState" to false)) {
                Xp.hookAll(vmClass, name) { chain ->
                    val result = chain.proceed()
                    modelRef = WeakReference(chain.thisObject)
                    if (inNumber != value) {
                        inNumber = value
                        trace(if (value) "stack folded" else "stack opened")
                        trace("MCNum: " + (if (value) "folded" else "opened")
                            + " (driving=" + driving.ifEmpty { "external" } + ")")
                        if (!value) lastOpenedAt = SystemClock.uptimeMillis()
                        // Folded to the count: MIUI keeps a blur material on every hidden row and
                        // blurs an empty rect each frame - ~130 a second, ~40% of systemui. Take
                        // the material off the rows that are not there (2026-11-17).
                        if (value) main.postDelayed({ clearHiddenGlass() }, 400L)
                        scheduleRemember(!value)
                        listeners.forEach { runCatching { it(value) } }
                    }
                    result
                }
            }
            available = true
        }.onFailure { Xp.log("MCNum: num state unhookable: $it") }
        // Let go in between, the stack rests as a pile (STACK) with the rows out on the lock
        // screen and no island: folding, a hands-up that lands there lands folded instead, so
        // the stack is either the island or the list, as ColorOS's is either capsule or list.
        runCatching {
            Xp.hookAll(vmClass, "switchStackStatus") { chain ->
                val target = chain.proceed()
                val info = chain.args.firstOrNull()
                val landing = target as Float
                val out: Float = runCatching { handsUp(chain.thisObject, info, landing) }
                    .getOrNull() ?: landing
                // The finger has just been lifted: whatever it landed on is what the user is
                // leaving the lock screen in, written before the animation's own transitions.
                runCatching { noteGesture(info, out, chain.thisObject) }
                out
            }
        }.onFailure { Xp.log("MCNum: hands-up unhookable: $it") }
        // Wake on actual native scrolling, including STACK <-> LIST without number-state callbacks.
        runCatching {
            val stack = Xp.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", classLoader)
            for (name in listOf("setOwnScrollY", "setOverScrolledPixels")) {
                runCatching {
                    Xp.hookAll(stack, name) { chain ->
                        val value = (chain.args.firstOrNull() as? Number)?.toFloat()
                        val target = chain.thisObject
                        val channel = if (name == "setOwnScrollY") 0
                            else if (chain.args.getOrNull(1) == true) 1 else 2
                        val changed = value != null && target != null &&
                            artworkScrollStates.getOrPut(target) { ArtworkScrollState() }.changed(channel, value)
                        val tracking = changed && LockIslands.tracksNotificationArtwork()
                        val before = if (tracking) position() else null
                        val result = chain.proceed()
                        if (tracking && position() != before) LockIslands.notificationArtworkMotion(before)
                        result
                    }
                }.onFailure { Xp.log("MCNum: $name artwork hook unavailable: $it") }
            }

        }.onFailure { Xp.log("MCNum: artwork motion hooks unavailable: $it") }
    }

    /** MiGlassCompat.setMiGlassCompat(View, float[]), to take a material off a hidden row. */
    private var glassClear: java.lang.reflect.Method? = null

    private fun clearHiddenGlass() {
        val m = glassClear ?: return
        val stack = MiniPlayerRuntime.stackForProbe() ?: return
        var cleared = 0
        fun walk(v: android.view.View) {
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            if (v.width == 0 && v.height == 0) {
                runCatching { m.invoke(null, v, null) }.onSuccess { cleared++ }
            }
        }
        runCatching { walk(stack) }
        if (cleared > 0) Xp.log("MCNum: glass cleared on " + cleared + " hidden views")
    }

    fun addListener(l: (Boolean) -> Unit) { listeners.addIfAbsent(l) }

    /** The islands take over the ordinary notifications' folding, or give it back. */
    fun setFolding(on: Boolean) {
        if (!available || folding == on) return
        folding = on
        trace("folding=$on")
        forceDefault(on)
        if (!on) hideCount(false)
    }

    // ── the state the user left the stack in（2026-11-16，用户要求）────────────
    // The lock screen comes up folded to its "N notifications" count whatever was left open:
    // the module forces NUMBER as the wake's default. What the user chooses by hand is kept, so
    // the next wake comes up the same way - an opened list or card stays opened instead of being
    // folded back into the island.
    private const val PREF_OPEN = "num_state_open"

    /** Until when the module's own moves are not the user's choice (its automatic folding). */
    @Volatile private var suppressUntil = 0L

    /** Why the module itself is moving the stack right now: its own moves are not the user's. */
    @Volatile private var driving = ""

    fun suppressRemembering() {
        suppressUntil = SystemClock.uptimeMillis() + 1500L
    }

    /** The stack is to come up opened: the user last left it that way. */
    @Volatile private var memOpen: Boolean? = null

    fun rememberedOpen(): Boolean {
        memOpen?.let { return it }
        val ctx = Main.sAppCtx ?: return false
        val v = ctx.getSharedPreferences("hmc_mini", android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_OPEN, false)
        memOpen = v
        return v
    }

    /**
     * What the user leaves the lock screen in is the memory - but only once it has settled: the
     * wake and the unlock move the stack several times in a second, and taking every transition
     * made the memory follow those instead of the user (2026-11-16). Written a moment after the
     * last change, and only while the lock screen is up with the screen on.
     */
    private var rememberGen = 0
    private var pendingOpen: Boolean? = null

    /**
     * Only one state is worth keeping: the user pulled the list out. Folded is the module's
     * default, so nothing has to be done for it - a "folded" memory just means "no action"
     * (2026-11-17, the user's rule).
     */
    private fun scheduleRemember(open: Boolean) {
        lastChoiceAt = SystemClock.uptimeMillis()
        if (!open) {
            // Back to the default: written at once, and the wake then does nothing at all.
            writeMemory(false, force = true)
            return
        }
        if (state() != "LIST") return
        writeMemory(true, force = true)
    }

    private val rememberRun = Runnable {
        val open = pendingOpen ?: return@Runnable
        if (SystemClock.uptimeMillis() < suppressUntil) return@Runnable
        if (!Main.keyguardUp() || !Main.screenOn()) return@Runnable
        writeMemory(open)
    }

    /**
     * The screen going off before the settle has run: expand or fold and lock at once, and the
     * wait lost it. Flush what is pending, before the screen-off folds the stack itself
     * (2026-11-16).
     */
    fun flushPending() {
        // Read what the stack shows as the screen goes: the pile it passes through is not a
        // choice, so only the capsule and the list decide (2026-11-17).
        val s = state()
        val open = when (s) {
            "LIST" -> true
            "NUMBER" -> false
            // The pile on the way out is not a choice: leave the memory alone.
            else -> null
        }
        // A gesture a moment ago is the user's latest word - the screen going off must not undo
        // it (2026-11-17).
        if (open == null || SystemClock.uptimeMillis() - lastChoiceAt < 3000L) {
            trace("MCNum: screen off -> " + s + " => left as is")
            return
        }
        pendingOpen = null
        rememberGen++
        main.removeCallbacks(rememberRun)
        trace("MCNum: screen off -> " + s + " => " + (if (open) "opened" else "folded"))
        writeMemory(open, force = true)
    }

    /** Writes the memory (and puts the wake's default where the user left the stack). */
    private fun writeMemory(open: Boolean, force: Boolean = false) {
        if (!force && SystemClock.uptimeMillis() < suppressUntil) return
        val ctx = Main.sAppCtx ?: return
        val p = ctx.getSharedPreferences("hmc_mini", android.content.Context.MODE_PRIVATE)
        if (p.getBoolean(PREF_OPEN, false) != open) {
            p.edit().putBoolean(PREF_OPEN, open).apply()
            memOpen = open
            trace(if (open) "remembered: opened" else "remembered: folded")

            // The wake reads the stack's default from the model's flow, and folding was turned on
            // only once: leaving the flow as it was meant the next wake opened the list anyway
            // (2026-11-16). Put it at what the user just chose.
            runCatching { forceDefault(true) }
        }
    }

    /** The stack was opened by the user a moment ago: an automatic fold right after it is not theirs. */
    private fun recentlyOpened(): Boolean =
        lastOpenedAt != 0L && SystemClock.uptimeMillis() - lastOpenedAt < 4000L

    @Volatile private var lastOpenedAt = 0L

    /** When the user last changed the stack by hand. */
    @Volatile private var lastChoiceAt = 0L


    /** The module's own list gesture has ended: folded home, or left open as the list. */
    fun noteSpreadEnd(folded: Boolean) {
        if (!folding) return
        suppressUntil = SystemClock.uptimeMillis() + 2500L
        trace("MCNum: spread end -> " + (if (folded) "folded" else "opened"))
        writeMemory(!folded, force = true)
    }

    /**
     * The finger let go of the stack: folded (the capsule) or opened (the list). The stack's own
     * hands-up target is its landing scroll, so the fold is told by where it lands against the
     * number state's target - with a tolerance, the two are not the same float - and by the
     * module's own fold gesture when its row was the one spread out (2026-11-16).
     */
    private fun noteGesture(info: Any?, target: Float, model: Any) {
        if (!folding || info == null) return
        val event = (Xp.getObjectField(info, "stackShowEvent") as? Enum<*>)?.name
        if (event != "HANDS_UP") return
        if (Xp.getObjectField(info, "isNumberState") == true) return
        val number = runCatching {
            val factory = Xp.getObjectField(Xp.getObjectField(model, "\$delegate_0"), "strategyFactory")
            val strategy = Xp.callMethod(Xp.getObjectField(factory, "numberStrategy"), "get")
            (Xp.callMethod(strategy, "calculateTargetYPosition", info) as Number).toFloat()
        }.getOrNull()
        val bySpread = LockIslands.spreadingNow() && MiniPlayerRuntime.spreadFolding()
        // Landing at (or below) the folded stack's own place: folded. Anything further up is the
        // list being left open.
        val folded = bySpread || (number != null && target <= number + 24f)
        trace("gesture target=" + target + " number=" + number + " -> "
            + (if (folded) "folded" else "opened"))
        suppressUntil = SystemClock.uptimeMillis() + 2500L
        writeMemory(!folded, force = true)
    }

    private fun handsUp(model: Any, info: Any?, target: Float): Float? {
        if (!folding || info == null || !LockIslands.foldsToCount()) return null
        if (LockIslands.keepsPileOnFold()) return null
        val event = (Xp.getObjectField(info, "stackShowEvent") as? Enum<*>)?.name
        if (event != "HANDS_UP" || Xp.getObjectField(info, "isNumberState") == true) return null
        if ((Xp.getObjectField(info, "normalNotifCount") as Number).toInt() <= 0) return null
        val factory = Xp.getObjectField(Xp.getObjectField(model, "\$\$delegate_0"), "strategyFactory")
        fun strategy(name: String) = Xp.callMethod(Xp.getObjectField(factory, name), "get")
        fun number() = (Xp.callMethod(strategy("numberStrategy"), "calculateTargetYPosition", info) as Number).toFloat()
        // The row spread out as the list and pulled toward home: folded. The stack's own hands-up
        // reads the list's top without the pull on it (nsslTopYPositionWithoutOverScroll), and a
        // pull down on the list moves only the pull - so it put the list back every time, and with
        // the media card and a focus card in it the list and the pile rest at the same place
        // (the clock's smallest): the row stood half folded (filmed 2026-09-30).
        if (LockIslands.spreadingNow() && MiniPlayerRuntime.spreadFolding()) {
            val number = number()
            trace("hands up with the row going home: folded ($target -> $number)")
            return number
        }
        val stack = (Xp.callMethod(strategy("stackStrategy"), "calculateTargetYPosition", info) as Number).toFloat()
        if (kotlin.math.abs(stack - target) >= 1f && !pileAt(model, info, target)) return null
        // Swiped up, it is the list being opened: with one notification the list rests where the
        // pile does, and a swipe up from the media card folded straight back (2026-10-24).
        if (MiniPlayerRuntime.stackSwipedUp) {
            trace("hands up at the pile after a swipe up: left open ($target)")
            return null
        }
        // The user left the stack open by hand: a hands-up that lands on the pile must not fold
        // it back into the island behind their back (2026-11-16). Only the settling right after
        // the open - a fold asked for later is the user's own.
        if (recentlyOpened()) {
            trace("hands up at the pile: left open (just opened by the user)")
            trace("MCNum: hands up left open (just opened by the user)")
            return null
        }
        val number = number()
        trace("hands up at the pile: folded instead ($target -> $number)")
        trace("MCNum: hands up folded instead (target=$target -> $number)")
        return number
    }

    /**
     * Whether the stack resting with its top at [top] reads as its pile (STACK): its own sum
     * (NotificationContainerViewModel's currentsStackState), the room under the top against the
     * number threshold and the pile's height. The strategy's pile top can be clamped to the
     * clock's smallest, where it is not where a hands-up leaves it.
     */
    private fun pileAt(model: Any, info: Any, top: Float): Boolean = runCatching {
        val sample = value(model, "getOnUpdateChildSampleStackInfo") ?: return false
        val bottom = (Xp.getObjectField(info, "notificationBottomOnKeyguard") as Number).toFloat()
        val scrim = (Xp.getObjectField(info, "scrimTopPadding") as Number).toFloat()
        val focus = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val room = bottom - top - scrim - focus
        val threshold = (Xp.callMethod(model, "getNumStateHeightThreshold", sample) as Number).toFloat()
        val pile = kotlin.math.ceil((Xp.callMethod(model, "stackingStateHeight\$default", model) as Number).toFloat())
        room >= threshold && room <= pile
    }.getOrDefault(false)

    // ---------------------------------------------------------------- the model and its state

    /** The stack's container model (NotificationContainerViewModel). */
    fun model(): Any? {
        modelRef?.get()?.let { return it }
        val stack = MiniPlayerRuntime.stackForProbe() ?: return null
        return runCatching {
            val controller = Xp.getObjectField(stack, "mController")
            val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
            val helper = Xp.getObjectField(injector, "numStateTouchHelper")
            Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
        }.getOrNull()?.also { modelRef = WeakReference(it) }
    }

    private fun value(owner: Any, getter: String): Any? =
        Xp.callMethod(Xp.callMethod(owner, getter), "getValue")

    /** NUMBER, STACK or LIST, as the stack reads itself now; null unread. */
    fun state(): String? = runCatching {
        (value(model() ?: return null, "getCurrentsStackState") as Enum<*>).name
    }.getOrNull()

    /** The stack is moving between states on its own (a scroll or the fold's fade). */
    fun busy(): Boolean = runCatching {
        val model = model() ?: return false
        value(model, "isNumStateAnimating") == true ||
            Xp.getObjectField(Xp.getObjectField(model, "isBeingDraggedOrAnimating"), "flow")
                .let { Xp.callMethod(it, "getValue") } == true
    }.getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    private fun stateConst(model: Any, name: String): Any {
        val now = value(model, "getCurrentsStackState") as Enum<*>
        return java.lang.Enum.valueOf(now.javaClass as Class<out Enum<*>>, name)
    }

    /** The native notification footer, in the same coordinates as the stack's target positions. */
    fun bottomOnKeyguard(): Float? = runCatching {
        val model = model() ?: return null
        val info = value(model, "getOnUpdateChildSampleStackInfo") ?: return null
        val event = Xp.callMethod(model, "notifStackStateToEvent", stateConst(model, "LIST"))
        val status = Xp.callMethod(model, "getKeyguardStackStatusInfo", info, event)
        (Xp.getObjectField(status, "notificationBottomOnKeyguard") as Number).toFloat()
            .takeIf { it.isFinite() && it > 0f }
    }.getOrNull()

    /** The scroll that puts the stack in [state], as SystemUI works it out. */
    fun scrollFor(model: Any, state: String): Int {
        val info = value(model, "getOnUpdateChildSampleStackInfo")!!
        val event = Xp.callMethod(model, "notifStackStateToEvent", stateConst(model, state))
        val status = Xp.callMethod(model, "getKeyguardStackStatusInfo", info, event)
        val top = (Xp.callMethod(model, "switchStackStatus", status) as Number).toFloat()
        val focus = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val bound = (Xp.callMethod(model, "positionWithoutMinScrollRange", focus) as Number).toFloat()
        return (bound - top).toInt()
    }

    /** The stack's own scroll now; null unread. */
    fun scrollY(): Int? = runCatching {
        val model = model() ?: return null
        (Xp.callMethod(Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get"),
            "getOwnScrollY") as Number).toInt()
    }.getOrNull()

    /**
     * The focus rows the stack has measured (its sample's focusNotifCount); null unread. The
     * list's place is worked out from this sample: asked before it counts rows just come in, the
     * place left them out and the stack read the result as its pile (STACK).
     */
    fun focusCount(): Int? = runCatching {
        val info = value(model() ?: return null, "getOnUpdateChildSampleStackInfo") ?: return null
        (Xp.getObjectField(info, "focusNotifCount") as Number).toInt()
    }.getOrNull()

    fun normalCount(): Int? = runCatching {
        val info = value(model() ?: return null, "getOnUpdateChildSampleStackInfo") ?: return null
        (Xp.getObjectField(info, "normalNotifCount") as Number).toInt()
    }.getOrNull()

    /**
     * The stack's pull past its scroll now (the model's overScrollAmount), 0 unread. A finger
     * pulling the list down toward the fold moves this, not the scroll: the scroll only follows
     * on the let-go, as the hands-up settles it (calculateNotifTop = base - scrollY + this).
     */
    fun overScroll(): Float {
        val fromModel = runCatching {
            val model = model() ?: return@runCatching 0f
            (Xp.callMethod(Xp.getObjectField(Xp.getObjectField(model, "overScrollAmount"), "flow"),
                "getValue") as Number).toFloat()
        }.getOrDefault(0f)
        // The stack's own reading of its pull at the top, should the model's not carry it.
        val fromStack = runCatching {
            (Xp.callMethod(MiniPlayerRuntime.stackForProbe() ?: return@runCatching 0f,
                "getCurrentOverScrollAmount", true) as Number).toFloat()
        }.getOrDefault(0f)
        return if (kotlin.math.abs(fromStack) > kotlin.math.abs(fromModel)) fromStack else fromModel
    }

    /** Both pulls apart, for the frame log. */
    fun overScrollParts(): String = runCatching {
        val m = model()?.let {
            (Xp.callMethod(Xp.getObjectField(Xp.getObjectField(it, "overScrollAmount"), "flow"), "getValue") as Number).toInt()
        }
        val s = MiniPlayerRuntime.stackForProbe()?.let {
            (Xp.callMethod(it, "getCurrentOverScrollAmount", true) as Number).toInt()
        }
        "$m/$s"
    }.getOrDefault("?")

    /**
     * The furthest the stack scrolls (NotificationStackScrollLayout.getScrollRange). The list's
     * place as the click-to-list strategy works it out can be past it - 658 against a stack that
     * went no further than 375 with the media card and a focus card in it (2026-09-30) - and the
     * spread, aimed there, stood at 0.57 for seconds, sent there again and again.
     */
    fun maxScroll(): Int? = runCatching {
        (Xp.callMethod(MiniPlayerRuntime.stackForProbe() ?: return null, "getScrollRange") as Number).toInt()
    }.getOrNull()

    /** Where the list rests: the strategy's place, as far as the stack can scroll. */
    fun listScroll(): Int? {
        val list = scrollTo("LIST") ?: return null
        val max = maxScroll() ?: return list
        val number = scrollTo("NUMBER") ?: return list
        return if (list > number) minOf(list, maxOf(max, number)) else list
    }

    // ---------------------------------------------------------------- the spread's frame log

    /** Every frame of the spread moving, for `op numstate`: the last FRAMES_KEPT of them. */
    private val frames = ArrayDeque<String>()
    private const val FRAMES_KEPT = 90

    fun frame(what: String) {
        synchronized(frames) {
            frames.addLast("${SystemClock.uptimeMillis() % 100000} $what")
            while (frames.size > FRAMES_KEPT) frames.removeFirst()
        }
    }

    fun framesText(): String = synchronized(frames) { frames.joinToString(" ; ") }

    /**
     * Where the stack's top is, as a scroll: the scroll less the pull on it, so a finger's pull
     * counts as it is made. On the same scale as [scrollFor]; null unread.
     */
    fun position(): Int? = scrollY()?.let { (it - overScroll()).toInt() }

    /** [scrollFor] for [state] as the stack is now; null unread. */
    fun scrollTo(state: String): Int? = runCatching { scrollFor(model() ?: return null, state) }.getOrNull()

    /** The stack scrolled to [state]: animated on the screen-on scroll's spring, or set. */
    fun goTo(state: String, anim: Boolean = true, why: String): String = runCatching {
        driving = why
        main.postDelayed({ if (driving == why) driving = "" }, 900L)
        val model = model() ?: return "no model"
        val stack = MiniPlayerRuntime.stackForProbe() ?: return "no stack"
        val y = scrollFor(model, state)
        val refactor = Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get")
        val scroller = Xp.callMethod(refactor, "getOverScroller")
        val cur = (Xp.callMethod(refactor, "getOwnScrollY") as Number).toInt()
        val injector = Xp.getObjectField(Xp.getObjectField(stack, "mController"), "mNsslControllerInjector")
        val done = if (!anim || cur == y) {
            Xp.callMethod(scroller, "abortAnimation")
            Xp.callMethod(stack, "setOwnScrollY", y)
            "scrollY $cur -> $y (set)"
        } else {
            val animator = Xp.callMethod(Xp.getObjectField(model, "notificationScreenOnOffAnimator"), "get")
            val ease = animator.javaClass.getField("screenOnScrollYEaseStyle").get(null)
            Xp.callMethod(scroller, "startFolmeScroll", 0, cur, y - cur, 0, ease)
            Xp.callMethod(stack, "animateScroll")
            Xp.callMethod(injector, "onStartScroll", y.toFloat(), topChangeType(injector, "NOTIFS_CHANGED"))
            "scrollY $cur -> $y (folme)"
        }
        trace("$why: to $state, $done")
        done
    }.getOrElse { trace("$why: to $state failed: $it"); "failed: $it" }

    /**
     * The stack put at scroll [y] this frame, no animation (a finger has it): as goTo's unanimated
     * way, a running scroll of its own stopped first. False when it could not be.
     */
    fun setScroll(y: Int): Boolean = runCatching {
        val model = model() ?: return false
        val stack = MiniPlayerRuntime.stackForProbe() ?: return false
        val refactor = Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get")
        if ((Xp.callMethod(refactor, "getOwnScrollY") as Number).toInt() == y) return true
        Xp.callMethod(Xp.callMethod(refactor, "getOverScroller"), "abortAnimation")
        Xp.callMethod(stack, "setOwnScrollY", y)
        true
    }.getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    private fun topChangeType(injector: Any, name: String): Any {
        val m = injector.javaClass.methods.first { it.name == "onStartScroll" && it.parameterTypes.size == 2 }
        return java.lang.Enum.valueOf(m.parameterTypes[1] as Class<out Enum<*>>, name)
    }

    // ---------------------------------------------------------------- the screen-on default

    /**
     * The state the lock screen wakes to is the user's setting default_keyguard_notif_state
     * (MiuiKeyguardNotificationSettingsRepository.defaultState). Folding, it is NUMBER in memory
     * only - its flow's value set, the setting itself untouched - so every wake finds the island
     * and not the rows. Given back, the setting's own value again. A change of the setting
     * while folding sets it back to the setting's, until the islands next turn this on.
     */
    private var forcedDefault = false

    private fun defaultFlow(): Any? = runCatching {
        val repo = Xp.getObjectField(model() ?: return null, "settingsRepository")
        Xp.getObjectField(Xp.getObjectField(repo, "defaultState"), "\$\$delegate_0")
    }.getOrNull()

    private fun forceDefault(on: Boolean) {
        runCatching {
            val flow = defaultFlow() ?: return
            val model = model() ?: return
            if (on) {
                // The wake's default is the state the user left the stack in (2026-11-16).
                val remembered = if (rememberedOpen()) "LIST" else "NUMBER"
                Xp.callMethod(flow, "setValue", stateConst(model, remembered))
                forcedDefault = true
                trace("default $remembered (in memory)")
                trace("MCNum: default " + remembered + " (in memory)")
            } else if (forcedDefault) {
                forcedDefault = false
                val setting = android.provider.Settings.System.getInt(
                    (Main.sAppCtx ?: return).contentResolver, "default_keyguard_notif_state", 2)
                val name = when (setting) { 3 -> "NUMBER"; 1 -> "LIST"; else -> "STACK" }
                Xp.callMethod(flow, "setValue", stateConst(model, name))
                trace("default back to the setting's $name")
            }
        }.onFailure { trace("default not set: $it") }
    }

    /**
     * Folding and the lock screen up with the stack as a pile: folded. Asked when the islands
     * show (after SystemUI starts, the first lock screen has not been through a wake), and
     * never while the stack is being moved or is the list the user opened.
     */
    @Volatile private var lastFoldAsk = 0L

    fun foldIfPiled(why: String) {
        if (!folding) return
        // The notifications change several times in a row while the stack settles: one check is
        // enough, and each one costs a state read and a scroll query (2026-11-17).
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastFoldAsk < 300L) return
        lastFoldAsk = nowMs
        main.removeCallbacks(foldCheck)
        foldWhy = why
        main.postDelayed(foldCheck, FOLD_SETTLE_MS)
    }

    private var foldWhy = ""
    private const val FOLD_SETTLE_MS = 350L

    private val foldCheck = Runnable {
        if (!folding || !LockIslands.foldsToCount()) return@Runnable
        // The list the spread opened, passing through the pile on its way: not folded back.
        if (LockIslands.keepsPileOnFold()) return@Runnable
        if (LockIslands.spreadingNow()) { trace("pile check skipped: spread ($foldWhy)"); return@Runnable }
        if (busy()) { foldIfPiled(foldWhy); return@Runnable }
        trace("fold check why=" + foldWhy + " state=" + state()
            + " rememberedOpen=" + rememberedOpen() + " busy=" + busy())
        // Only the pulled-out list needs doing: folded is the module's own default, so the wake
        // leaves it alone. Opened is put back whatever the lock screen came up in (2026-11-17).
        if (rememberedOpen()) {
            if (state() != "LIST") {
                suppressRemembering()
                goTo("LIST", anim = false, why = "remembered open ($foldWhy)")
            }
        }
    }

    // ---------------------------------------------------------------- the count line

    /**
     * The stack's own "N个通知" line (NotificationNumStateView) sits inside the island's pill:
     * hidden while the island stands for it. On its children's transitionAlpha (the zen mark, the
     * divider, the count), which nothing of SystemUI's writes: the line's own transitionAlpha is
     * the keyguard's fade (scaleAndAlpha, NotificationNumStateViewBinder), and showing the line
     * by setting that to 1 put "勿扰" over the bouncer, where the keyguard had faded it out (#42).
     */
    fun hideCount(hide: Boolean) {
        val v = countView() as? ViewGroup ?: return
        val want = if (hide) 0f else 1f
        var changed = false
        for (k in 0 until v.childCount) {
            val c = v.getChildAt(k)
            if (c.transitionAlpha != want) {
                c.transitionAlpha = want
                changed = true
            }
        }
        if (changed) trace(if (hide) "count hidden" else "count shown")
    }

    private fun countView(): View? {
        countRef?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        val root = MiniPlayerRuntime.stackForProbe()?.rootView ?: return null
        val queue = ArrayDeque<View>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.endsWith("NotificationNumStateView")) {
                countRef = WeakReference(v)
                return v
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) queue.add(v.getChildAt(k))
        }
        return null
    }
}
