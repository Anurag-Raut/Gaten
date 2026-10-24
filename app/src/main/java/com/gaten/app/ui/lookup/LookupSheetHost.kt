/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import com.facebook.react.ReactHost
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.runtime.ReactSurfaceImpl
import com.gaten.app.MainApplication

/**
 * The React Native side of the lookup overlay.
 *
 * The overlay window itself stays native — the accessibility service owns it, freezes the capture and
 * draws the selection — but every sheet inside it is the app's own RN UI. This host mounts one
 * [ReactSurface] onto the **application's existing [ReactHost]** ([MainApplication.reactHost]), so
 * the sheet shares the instance, JS bundle, TurboModules and Hermes runtime the main app already
 * runs: there is no second runtime and no Activity. [show] adds the surface's root view to the
 * overlay's window, [update] pushes a new state payload into it, and [hide] stops and detaches it.
 *
 * All calls are main-thread, like the window they add to.
 */
object LookupSheetHost {

    /** One root surface for the life of the process, reused across overlay open/close. */
    private var surface: ReactSurface? = null

    /** The overlay's own actions, so the surface's calls are answered by whoever owns the session. */
    private var actions: Actions? = null

    /** What the sheet can ask the overlay to do. */
    interface Actions {
        /** Close the lookup and dismiss the overlay. */
        fun onClose()
        /** Open the dictionary search (the cover is part of this same surface). */
        fun onOpenSearch()
        /** Discard the current result and return to selection. */
        fun onSelectAgain()
        /** IME/focus state changed, so the overlay can attribute a window focus loss correctly. */
        fun onTextEntryActive(active: Boolean)
        /** The surface has drawn its first frame. */
        fun onReady()
    }

    /** True once a surface has been created; used to decide whether the window needs the host. */
    val isMounted: Boolean
        get() = surface?.isRunning == true

    /**
     * Mounts the sheet into [root], creating the surface on first use and starting it. [initialProps]
     * is the first state payload; later states go through [update].
     */
    fun show(
        context: Context,
        root: ViewGroup,
        moduleName: String,
        initialProps: Bundle,
        hostActions: Actions
    ) {
        actions = hostActions
        val app = context.applicationContext as? MainApplication ?: return
        val reactHost: ReactHost = app.reactHost
        val current = surface ?: reactHost
            .createSurface(context, moduleName, initialProps)
            .also { surface = it }
        (current as? ReactSurfaceImpl)?.updateInitProps(initialProps)
        val view = current.view ?: return
        if (view.parent == null) {
            root.addView(
                view,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        current.start()
    }

    /** Pushes a new state payload into the mounted surface. */
    fun update(initialProps: Bundle) {
        (surface as? ReactSurfaceImpl)?.updateInitProps(initialProps)
    }

    /** Stops the surface and takes its view out of the overlay window. */
    fun hide(root: ViewGroup) {
        val current = surface ?: return
        val view = current.view
        if (view != null && view.parent === root) {
            root.removeView(view)
        }
        current.stop()
    }

    /** Called by [GatenLookupSheetModule] when JS asks the overlay to do something. */
    fun dispatchClose() {
        actions?.onClose()
    }

    /** @see dispatchClose */
    fun dispatchOpenSearch() {
        actions?.onOpenSearch()
    }

    /** @see dispatchClose */
    fun dispatchSelectAgain() {
        actions?.onSelectAgain()
    }

    /** @see dispatchClose */
    fun dispatchTextEntryActive(active: Boolean) {
        actions?.onTextEntryActive(active)
    }

    /** @see dispatchClose */
    fun dispatchReady() {
        actions?.onReady()
    }
}
