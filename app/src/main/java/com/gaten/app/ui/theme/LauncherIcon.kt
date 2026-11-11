/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.gaten.app.MainActivity
import java.util.concurrent.Executors

/**
 * The launcher tile, on the icon the app's own theme asks for.
 *
 * Fixed light/dark icons and a night-qualified System icon belong to aliases over the disposable
 * LauncherActivity. System follows Android even when this process is gone. A single worker reads
 * the latest preference for each sync, so rapid changes settle on the last saved mode.
 */
object LauncherIcon {
    // These names match the manifest and must stay stable across updates.
    private val aliasNames = mapOf(
        ThemeMode.System to "com.gaten.app.LauncherIconSystem",
        ThemeMode.Light to "com.gaten.app.LauncherIconLight",
        ThemeMode.Dark to "com.gaten.app.LauncherIconDark",
    )

    private const val TAG = "LauncherIcon"

    /** One thread, so no two switches are ever in flight and the last one queued is the last applied. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, TAG).apply { isDaemon = true }
    }

    /**
     * Resyncs on MainActivity resume (including first launch, update and preference restore).
     * Wait for that resume on startup so the launcher dispatcher has finished before aliases change.
     */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) sync(application)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Queues package-manager work off the caller's thread; logs failures and retries on the next sync. */
    fun sync(context: Context) {
        val applicationContext = context.applicationContext
        executor.execute {
            try {
                switchTo(applicationContext)
            } catch (error: Exception) {
                Log.e(TAG, "Could not switch the launcher icon; the entry keeps the one it has.", error)
            }
        }
    }

    /** Enables the chosen alias before disabling the others; leaves an already correct selection alone. */
    private fun switchTo(context: Context) {
        val packageManager = context.packageManager
        val wantedName = aliasNames.getValue(ThemePreferences.themeMode(context))
        val aliases = aliasNames.values.map { ComponentName(context.packageName, it) }
        val wanted = aliases.first { it.className == wantedName }
        if (aliases.all { isEnabled(packageManager, it) == (it == wanted) }) return

        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // One transaction: a launcher reading the package in between must never find neither entry.
            packageManager.setComponentEnabledSettings(
                aliases.map {
                    PackageManager.ComponentEnabledSetting(
                        it, if (it == wanted) enabled else disabled, PackageManager.DONT_KILL_APP,
                    )
                },
            )
        } else {
            // Nothing atomic before 33: enable first, so there is always one entry for a launcher to find.
            packageManager.setComponentEnabledSetting(wanted, enabled, PackageManager.DONT_KILL_APP)
            aliases.filter { it != wanted }.forEach {
                packageManager.setComponentEnabledSetting(it, disabled, PackageManager.DONT_KILL_APP)
            }
        }
    }

    /** The state the app set, or — before its first switch — the manifest's own `android:enabled`. */
    private fun isEnabled(packageManager: PackageManager, alias: ComponentName): Boolean =
        when (packageManager.getComponentEnabledSetting(alias)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> packageManager.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
        }
}
