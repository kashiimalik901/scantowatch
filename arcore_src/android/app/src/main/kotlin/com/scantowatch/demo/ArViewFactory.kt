package com.scantowatch.demo

import android.app.Activity
import android.content.Context
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Builds the AR platform view and keeps a handle on the live instance so
 * MainActivity can forward lifecycle events to it.
 */
class ArViewFactory(
    private val activity: Activity,
    private val channel: MethodChannel
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    var current: ArVideoView? = null
        private set

    override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
        val view = ArVideoView(activity, channel)
        current = view
        // The view is created when the Flutter widget mounts, which is after the
        // activity's own onResume has already fired — so resume it here or the
        // session never starts on first launch.
        view.resume()
        return view
    }
}
