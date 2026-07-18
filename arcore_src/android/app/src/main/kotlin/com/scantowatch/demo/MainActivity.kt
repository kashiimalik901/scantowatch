package com.scantowatch.demo

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var factory: ArViewFactory? = null

    companion object {
        private const val CHANNEL = "scantowatch/ar"
        private const val VIEW_TYPE = "scantowatch/ar_view"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        val f = ArViewFactory(this, channel)
        factory = f

        flutterEngine.platformViewsController.registry
            .registerViewFactory(VIEW_TYPE, f)
    }

    override fun onResume() {
        super.onResume()
        // Also covers returning from the "Google Play Services for AR" install flow.
        factory?.current?.resume()
    }

    override fun onPause() {
        factory?.current?.pause()
        super.onPause()
    }
}
