/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

@SysUISingleton
class MomentArcController
@Inject
constructor(
    private val context: Context,
    private val userTracker: UserTracker,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxLifetimeRunnable = Runnable { hide() }
    private val overlayGeneration = AtomicLong()
    // One lazily created worker for optional app/shortcut icon IPC.
    // It exits when idle; no permanent background daemon.
    private val iconExecutor = ThreadPoolExecutor(
        0, 1, 15L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(16),
        { runnable -> Thread(runnable, "MomentArc-icon").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    @Volatile private var gestureEnabled = false

    fun refreshEnabledState() {
        gestureEnabled = try {
            val userId = userTracker.userId
            Settings.Secure.getIntForUser(
                context.contentResolver, Settings.Secure.MOMENT_ENABLED, 0, userId,
            ) != 0 && Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.MOMENT_ARC_GESTURE_ENABLED, 1, userId,
            ) != 0
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read MomentArc settings", e)
            false
        }
        if (!gestureEnabled) hide()
    }
    private var overlayView: MomentArcView? = null
    private var lastWindowManagerErrorLogTime = Long.MIN_VALUE
    private var suppressedWindowManagerErrorCount = 0

    fun show(isLeft: Boolean, initialTouchX: Float = -1f, initialTouchY: Float = -1f) {
        hide()
        if (!isMomentArcEnabled()) return
        val generation = overlayGeneration.get()

        val view = MomentArcView(context, isLeft)
        val innerTargets = getTargetSlots(INNER_RING_TARGETS, INNER_MAX_ICONS - 1)
        val outerTargets = getTargetSlots(OUTER_RING_TARGETS, OUTER_MAX_ICONS)
        val displayedInnerTargets = ArrayList<MomentArcTarget?>()
        val displayedOuterTargets = ArrayList<MomentArcTarget?>()

        innerTargets.forEach { target ->
            val iconView = target?.let { createIconView(it, generation) }
            if (iconView != null) {
                view.addView(iconView)
            } else {
                view.addView(View(context).apply { visibility = View.INVISIBLE })
            }
            displayedInnerTargets.add(target)
        }
        view.addView(ImageView(context).apply { setImageResource(R.drawable.ic_moment_arc_all_apps) })
        outerTargets.forEach { target ->
            val iconView = target?.let { createIconView(it, generation) }
            if (iconView != null) {
                view.addView(iconView)
            } else {
                view.addView(View(context).apply { visibility = View.INVISIBLE })
            }
            displayedOuterTargets.add(target)
        }

        view.setOnIconLaunchListener { index ->
            if (!isMomentArcEnabled()) {
                hide()
                return@setOnIconLaunchListener
            }
            when {
                index < INNER_MAX_ICONS - 1 -> displayedInnerTargets.getOrNull(index)?.let(::launch)
                index == INNER_MAX_ICONS - 1 -> launchAllApps()
                else -> displayedOuterTargets.getOrNull(index - INNER_MAX_ICONS)?.let(::launch)
            }
            hide()
        }
        view.setOnDismissListener(::hide)
        if (initialTouchX >= 0f && initialTouchY >= 0f) {
            view.setInitialTouchPoint(initialTouchX, initialTouchY)
        }
        try {
            windowManager.addView(view, MomentArcView.createLayoutParams())
            overlayView = view
            mainHandler.postDelayed(maxLifetimeRunnable, MAX_LIFETIME_MS)
        } catch (e: Exception) {
            logWindowManagerFailure("Failed to add MomentArc view", e)
        }
    }

    fun onTouchCoordinates(x: Float, y: Float, isUp: Boolean, isCancelled: Boolean) {
        if (!isMomentArcEnabled() || isCancelled) {
            overlayView?.dispatchTouchCoordinates(x, y, false, true) ?: hide()
            return
        }
        overlayView?.dispatchTouchCoordinates(x, y, isUp, false)
    }

    fun hide() {
        mainHandler.removeCallbacks(maxLifetimeRunnable)
        overlayGeneration.incrementAndGet()
        val view = overlayView ?: return
        overlayView = null
        try {
            view.animateExit {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    logWindowManagerFailure("Failed to remove MomentArc view", e)
                }
            }
        } catch (e: RuntimeException) {
            logWindowManagerFailure("Failed to animate MomentArc exit", e)
        }
        // An animation end callback may be skipped when SystemUI cancels a
        // window transition. Enforce bounded cleanup without polling or killing
        // SystemUI; the visual overlay must never outlive its gesture.
        mainHandler.postDelayed({
            if (view.isAttachedToWindow) {
                try {
                    windowManager.removeViewImmediate(view)
                } catch (e: RuntimeException) {
                    logWindowManagerFailure("Failed to force-remove stale MomentArc", e)
                }
            }
        }, MAX_EXIT_CLEANUP_MS)
    }

    // Hot-path broadcasts read one cached boolean, not SettingsProvider
    // Binder state for every ACTION_UPDATE_MOMENT_ARC_TOUCH event.
    fun isMomentArcEnabled(): Boolean = gestureEnabled

    private fun createIconView(target: MomentArcTarget, generation: Long): ImageView {
        val iconView = ImageView(context).apply {
            setImageDrawable(context.packageManager.defaultActivityIcon)
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val size = min(view.width, view.height)
                        if (size <= 0) {
                            outline.setEmpty()
                            return
                        }
                        val left = (view.width - size) / 2
                        val top = (view.height - size) / 2
                        outline.setRoundRect(left, top, left + size, top + size, size / 2f)
                    }
                }
            clipToOutline = true
        }
        // Never query PackageManager or LauncherApps synchronously while
        // SystemUI handles gesture events. Drop stale results after dismissal.
        iconExecutor.execute {
            if (overlayGeneration.get() != generation) return@execute
            val icon = runCatching { loadIcon(target) }
                .onFailure { Log.w(TAG, "Failed to load MomentArc icon", it) }
                .getOrNull() ?: return@execute
            mainHandler.post {
                if (overlayGeneration.get() == generation && overlayView != null) {
                    iconView.setImageDrawable(icon)
                }
            }
        }
        return iconView
    }

    private fun loadIcon(target: MomentArcTarget): Drawable {
        return when (target) {
            is MomentArcTarget.App -> fallbackAppIcon(target.packageName)
            is MomentArcTarget.Shortcut -> {
                val info = getShortcutInfo(target) ?: return fallbackAppIcon(target.packageName)
                launcherApps.getShortcutBadgedIconDrawable(
                    info,
                    context.resources.displayMetrics.densityDpi,
                ) ?: fallbackAppIcon(target.packageName)
            }
        }
    }

    private fun fallbackAppIcon(packageName: String): Drawable {
        val packageManager = currentUserContext().packageManager
        return packageManager.getApplicationInfo(packageName, 0).loadIcon(packageManager)
    }

    private fun launch(target: MomentArcTarget) {
        when (target) {
            is MomentArcTarget.App -> launchPackage(target.packageName)
            is MomentArcTarget.Shortcut -> launchShortcut(target)
        }
    }

    private fun launchPackage(packageName: String) {
        val intent = currentUserContext().packageManager.getLaunchIntentForPackage(packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching {
            context.startActivityAsUser(intent, momentOptions().toBundle(), userTracker.userHandle)
        }.onFailure { Log.w(TAG, "Failed to launch package $packageName", it) }
    }

    private fun launchShortcut(target: MomentArcTarget.Shortcut) {
        if (target.userId != userTracker.userId) {
            Log.w(TAG, "Ignoring shortcut target for a non-current user")
            return
        }
        val options = momentOptions().apply { setApplyMultipleTaskFlagForShortcut(true) }
        runCatching {
            launcherApps.startShortcut(
                target.packageName,
                target.shortcutId,
                null,
                options.toBundle(),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(TAG, "Failed to launch shortcut ${target.packageName}/${target.shortcutId}", it)
        }
    }

    private fun launchAllApps() {
        val intent = Intent().apply {
            setClassName(SETTINGS_PACKAGE, SETTINGS_ALL_APPS_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        runCatching {
            context.startActivityAsUser(intent, momentOptions().toBundle(), userTracker.userHandle)
        }.onFailure { Log.w(TAG, "Failed to launch all apps picker", it) }
    }

    private fun momentOptions() =
        ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_MOMENT)
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }

    private fun getTargetSlots(key: String, slotCount: Int): List<MomentArcTarget?> {
        val entries = Settings.System.getStringForUser(context.contentResolver, key, userTracker.userId)
            .orEmpty()
            .split(ENTRY_SEPARATOR)
        return List(slotCount) { index ->
            entries.getOrNull(index)?.takeUnless { it.trim() == EMPTY_TARGET }?.let(::parseTarget)
        }
    }

    private fun parseTarget(rawEntry: String): MomentArcTarget? {
        val entry = rawEntry.trim()
        if (entry.isBlank()) return null
        if (!entry.startsWith(APP_PREFIX) && !entry.startsWith(SHORTCUT_PREFIX)) {
            return MomentArcTarget.App(entry)
        }
        if (entry.startsWith(APP_PREFIX)) {
            return Uri.decode(entry.removePrefix(APP_PREFIX)).trim().takeIf(String::isNotBlank)
                ?.let(MomentArcTarget::App)
        }
        val parts = entry.split(FIELD_SEPARATOR, limit = 4)
        if (parts.size !in 3..4) return malformedShortcut(entry)
        val explicitUser = parts.size == 4
        val userId = if (explicitUser) parts[1].toIntOrNull() else userTracker.userId
        val packageName = Uri.decode(parts[if (explicitUser) 2 else 1]).trim()
        val shortcutId = Uri.decode(parts[if (explicitUser) 3 else 2]).trim()
        if (userId == null || packageName.isBlank() || shortcutId.isBlank()) {
            return malformedShortcut(entry)
        }
        if (userId != userTracker.userId) {
            Log.w(TAG, "Ignoring MomentArc shortcut target for a non-current user")
            return null
        }
        return MomentArcTarget.Shortcut(packageName, shortcutId, userId)
    }

    private fun malformedShortcut(entry: String): MomentArcTarget? {
        Log.w(TAG, "Ignoring malformed MomentArc shortcut entry: $entry")
        return null
    }

    private fun getShortcutInfo(target: MomentArcTarget.Shortcut): ShortcutInfo? =
        queryShortcut(target, LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED)
            ?: queryShortcut(target, LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS)

    private fun queryShortcut(target: MomentArcTarget.Shortcut, flags: Int): ShortcutInfo? =
        runCatching {
            launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery()
                    .setPackage(target.packageName)
                    .setShortcutIds(listOf(target.shortcutId))
                    .setQueryFlags(flags),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(TAG, "Failed to query shortcut ${target.packageName}/${target.shortcutId}", it)
        }.getOrNull()?.firstOrNull()

    private fun currentUserContext() = context.createContextAsUser(userTracker.userHandle, 0)

    @Synchronized
    private fun logWindowManagerFailure(message: String, error: Exception) {
        val now = SystemClock.elapsedRealtime()
        if (lastWindowManagerErrorLogTime != Long.MIN_VALUE &&
            now - lastWindowManagerErrorLogTime < WINDOW_MANAGER_ERROR_LOG_INTERVAL_MS
        ) {
            suppressedWindowManagerErrorCount++
            return
        }
        val suffix = if (suppressedWindowManagerErrorCount > 0) {
            " ($suppressedWindowManagerErrorCount similar failures suppressed)"
        } else ""
        Log.w(TAG, message + suffix, error)
        lastWindowManagerErrorLogTime = now
        suppressedWindowManagerErrorCount = 0
    }

    private sealed interface MomentArcTarget {
        data class App(val packageName: String) : MomentArcTarget
        data class Shortcut(val packageName: String, val shortcutId: String, val userId: Int) :
            MomentArcTarget
    }

    private companion object {
        const val TAG = "MomentArc"
        const val WINDOW_MANAGER_ERROR_LOG_INTERVAL_MS = 30_000L
        const val MAX_LIFETIME_MS = 10_000L
        const val MAX_EXIT_CLEANUP_MS = 500L
        const val INNER_MAX_ICONS = 6
        const val OUTER_MAX_ICONS = 7
        const val ENTRY_SEPARATOR = "|"
        const val APP_PREFIX = "app:"
        const val SHORTCUT_PREFIX = "shortcut:"
        const val EMPTY_TARGET = "empty:"
        const val FIELD_SEPARATOR = ":"
        const val INNER_RING_TARGETS = "moment_arc_selected_targets"
        const val OUTER_RING_TARGETS = "moment_arc_outer_ring_selected_targets"
        const val SETTINGS_PACKAGE = "org.uwuaosp.settingsext"
        const val SETTINGS_ALL_APPS_ACTIVITY =
            "org.uwuaosp.settingsext.moment.MomentAllAppsActivity"
    }
}
