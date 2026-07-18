import 'package:flutter/foundation.dart';  // Factory
import 'package:flutter/gestures.dart';    // OneSequenceGestureRecognizer
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';   // PlatformViewHitTestBehavior
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const ScanToWatchApp());
}

class ScanToWatchApp extends StatelessWidget {
  const ScanToWatchApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ScanToWatch',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true),
      home: const ArScreen(),
    );
  }
}

class ArScreen extends StatefulWidget {
  const ArScreen({super.key});

  @override
  State<ArScreen> createState() => _ArScreenState();
}

enum _Stage { requesting, denied, running, failed }

class _ArScreenState extends State<ArScreen> {
  static const _channel = MethodChannel('scantowatch/ar');
  static const _viewType = 'scantowatch/ar_view';

  _Stage _stage = _Stage.requesting;
  bool _tracking = false;
  String _error = '';

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_onNative);
    _requestCamera();
  }

  /// ARCore cannot create a session without CAMERA, so this must resolve before
  /// the platform view is mounted.
  Future<void> _requestCamera() async {
    final status = await Permission.camera.request();
    if (!mounted) return;
    setState(() {
      _stage = status.isGranted ? _Stage.running : _Stage.denied;
    });
  }

  Future<dynamic> _onNative(MethodCall call) async {
    if (!mounted) return null;
    switch (call.method) {
      case 'tracking':
        setState(() => _tracking = call.arguments as bool);
        break;
      case 'error':
        setState(() {
          _error = call.arguments as String;
          _stage = _Stage.failed;
        });
        break;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: switch (_stage) {
        _Stage.requesting => const _Centered(
            icon: Icons.camera_alt_outlined,
            title: 'Camera permission',
            message: 'Allow camera access to start the AR experience.',
          ),
        _Stage.denied => _Centered(
            icon: Icons.no_photography_outlined,
            title: 'Camera blocked',
            message:
                'This demo needs the camera. Enable it in Settings, then reopen '
                'the app.',
            action: ('Open settings', openAppSettings),
          ),
        _Stage.failed => _Centered(
            icon: Icons.error_outline,
            title: 'AR unavailable',
            message: _error,
          ),
        _Stage.running => Stack(
            fit: StackFit.expand,
            children: [
              const _ArPlatformView(viewType: _viewType),
              SafeArea(
                child: Align(
                  alignment: Alignment.topCenter,
                  child: Padding(
                    padding: const EdgeInsets.only(top: 14),
                    child: _StatusPill(tracking: _tracking),
                  ),
                ),
              ),
            ],
          ),
      },
    );
  }
}

/// A GLSurfaceView cannot be composited correctly in Flutter's default
/// virtual-display mode — it renders black or z-fights with Flutter's own
/// surface. Hybrid composition (initExpensiveAndroidView) is required.
class _ArPlatformView extends StatelessWidget {
  const _ArPlatformView({required this.viewType});

  final String viewType;

  @override
  Widget build(BuildContext context) {
    return PlatformViewLink(
      viewType: viewType,
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
      ),
      onCreatePlatformView: (params) {
        return PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: TextDirection.ltr,
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
      },
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.tracking});

  final bool tracking;

  @override
  Widget build(BuildContext context) {
    final colour = tracking ? const Color(0xFF34D0A1) : const Color(0xFFF5B544);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 9),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.66),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(color: colour, shape: BoxShape.circle),
          ),
          const SizedBox(width: 9),
          Text(
            tracking ? 'Playing' : 'Looking for image…',
            style: const TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w600,
              color: Colors.white,
            ),
          ),
        ],
      ),
    );
  }
}

class _Centered extends StatelessWidget {
  const _Centered({
    required this.icon,
    required this.title,
    required this.message,
    this.action,
  });

  final IconData icon;
  final String title;
  final String message;
  final (String, VoidCallback)? action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(34),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 46, color: Colors.white54),
            const SizedBox(height: 20),
            Text(title,
                style: const TextStyle(
                    fontSize: 21, fontWeight: FontWeight.w700)),
            const SizedBox(height: 10),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 14.5, height: 1.55, color: Color(0xFF9AA7BD)),
            ),
            if (action != null) ...[
              const SizedBox(height: 24),
              FilledButton(onPressed: action!.$2, child: Text(action!.$1)),
            ],
          ],
        ),
      ),
    );
  }
}
