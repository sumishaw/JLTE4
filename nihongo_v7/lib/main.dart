import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(scaffoldBackgroundColor: Colors.black),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {

  static const platform = MethodChannel('overlay_channel');

  String originalText  = "";
  String englishText   = "Waiting for captions...";
  String hindiText     = "";
  bool   isRunning     = false;
  bool   hasOverlay    = false;
  bool   hasAccessibility = false;
  String targetLang    = "english"; // "english" or "hindi"
  String statusMsg     = "";
  int    translationCount = 0;
  Timer? pollTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Push-based updates from native side (instant, no polling lag)
    platform.setMethodCallHandler((call) async {
      if (call.method == "onTranslation" && call.arguments is Map) {
        final args = call.arguments as Map;
        final orig = args["original"]?.toString() ?? "";
        final en   = args["english"]?.toString()  ?? "";
        final hi   = args["hindi"]?.toString()    ?? "";
        if ((en.isNotEmpty || hi.isNotEmpty) && mounted) {
          setState(() {
            originalText = orig;
            englishText  = en.isNotEmpty ? en : englishText;
            hindiText    = hi;
            translationCount++;
          });
        }
      }
    });

    _checkPermissions();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkPermissions();
  }

  Future<void> _checkPermissions() async {
    try {
      final overlay = await platform.invokeMethod<bool>('hasOverlayPermission') ?? false;
      final access  = await platform.invokeMethod<bool>('checkAccessibilityEnabled') ?? false;
      if (mounted) setState(() { hasOverlay = overlay; hasAccessibility = access; });
    } catch (_) {}
  }

  Future<void> _setLanguage(String lang) async {
    await platform.invokeMethod('setTargetLanguage', {'language': lang});
    setState(() {
      targetLang = lang;
      hindiText  = "";
    });
  }

  Future<void> _start() async {
    if (!hasOverlay) {
      await platform.invokeMethod('requestOverlayPermission');
      setState(() => statusMsg = '⚠️ Allow "Display over other apps" → come back → tap START again');
      return;
    }
    if (!hasAccessibility) {
      await platform.invokeMethod('openAccessibilitySettings');
      setState(() => statusMsg = '⚠️ Find "Caption Lens" → Enable it → come back → tap START');
      return;
    }
    await platform.invokeMethod('startOverlay');
    setState(() {
      isRunning = true;
      translationCount = 0;
      statusMsg = '';
      englishText = "Watching for captions...";
      hindiText   = "";
      originalText = "";
    });

    // Backup polling — covers the case where push callback is missed
    pollTimer?.cancel();
    pollTimer = Timer.periodic(const Duration(milliseconds: 600), (_) async {
      try {
        final r = await platform.invokeMethod('getLatestTranslation');
        if (r is Map && mounted) {
          final orig = r["original"]?.toString() ?? "";
          final en   = r["english"]?.toString()  ?? "";
          final hi   = r["hindi"]?.toString()    ?? "";
          if (en.isNotEmpty && en != englishText) {
            setState(() {
              originalText = orig;
              englishText  = en;
              hindiText    = hi;
              translationCount++;
            });
          }
        }
      } catch (_) {}
    });
  }

  Future<void> _stop() async {
    pollTimer?.cancel();
    await platform.invokeMethod('stopOverlay');
    if (mounted) setState(() {
      isRunning = false;
      statusMsg = "";
      englishText  = "Waiting for captions...";
      hindiText    = "";
      originalText = "";
    });
  }

  @override
  void dispose() {
    pollTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final showHindi   = targetLang == "hindi" && hindiText.isNotEmpty;
    final displayText = showHindi ? hindiText : englishText;

    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(child: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [

          // ── Header ──────────────────────────────────────────────────────
          Row(children: [
            const Text('🌐', style: TextStyle(fontSize: 28)),
            const SizedBox(width: 10),
            const Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Caption Lens',
                    style: TextStyle(color: Colors.white, fontSize: 22,
                        fontWeight: FontWeight.bold)),
                Text('Translates captions from ANY app',
                    style: TextStyle(color: Colors.white38, fontSize: 11)),
              ],
            )),
            if (isRunning) Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                  color: Colors.red, borderRadius: BorderRadius.circular(12)),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                const Icon(Icons.fiber_manual_record, color: Colors.white, size: 8),
                const SizedBox(width: 4),
                Text('$translationCount',
                    style: const TextStyle(color: Colors.white, fontSize: 11,
                        fontWeight: FontWeight.bold)),
              ]),
            ),
          ]),

          const SizedBox(height: 20),

          // ── Permissions ──────────────────────────────────────────────────
          _permRow(Icons.accessibility_new, 'Accessibility Service',
              'Required to read captions from screen', hasAccessibility, () async {
            await platform.invokeMethod('openAccessibilitySettings');
          }),
          const SizedBox(height: 8),
          _permRow(Icons.picture_in_picture_alt, 'Display over apps',
              'Required to show floating overlay', hasOverlay, () async {
            await platform.invokeMethod('requestOverlayPermission');
          }),

          const SizedBox(height: 20),

          // ── Works with ──────────────────────────────────────────────────
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: const Color(0xFF0a1628),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: Colors.blue.withOpacity(0.2)),
            ),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Works with', style: TextStyle(
                  color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
              const SizedBox(height: 10),
              Wrap(spacing: 8, runSpacing: 8, children: [
                _chip('📺 YouTube'), _chip('🌐 Chrome'), _chip('🦊 Firefox'),
                _chip('🎬 VLC'),    _chip('🎞 Netflix'), _chip('🎦 Prime'),
                _chip('📱 Live Caption'), _chip('+ Any app'),
              ]),
              const SizedBox(height: 10),
              const Text('Detects', style: TextStyle(
                  color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                _chip('🇯🇵 Japanese'), _chip('🇨🇳 Chinese'), _chip('🇰🇷 Korean'),
                _chip('🇫🇷 French'),  _chip('🇩🇪 German'),  _chip('🇪🇸 Spanish'),
                _chip('🇹🇷 Turkish'), _chip('🇬🇧 English'),
              ]),
            ]),
          ),

          const SizedBox(height: 20),

          // ── Target language selector ─────────────────────────────────────
          const Text('Translate to',
              style: TextStyle(color: Colors.white54, fontSize: 12,
                  fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          Row(children: [
            _langBtn('🇬🇧 English', 'english'),
            const SizedBox(width: 10),
            _langBtn('🇮🇳 Hindi', 'hindi'),
          ]),

          const SizedBox(height: 20),

          // ── Original caption ─────────────────────────────────────────────
          if (originalText.isNotEmpty && originalText != displayText) ...[
            const Text('Detected caption',
                style: TextStyle(color: Colors.white38, fontSize: 12)),
            const SizedBox(height: 6),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                  color: Colors.white10,
                  borderRadius: BorderRadius.circular(10)),
              child: Text(originalText,
                  style: const TextStyle(color: Colors.white60,
                      fontSize: 16, letterSpacing: 0.5)),
            ),
            const SizedBox(height: 12),
          ],

          // ── Translation output ───────────────────────────────────────────
          Text(
            targetLang == "hindi" ? '🇮🇳 Hindi Translation' : '🇬🇧 English Translation',
            style: const TextStyle(color: Colors.greenAccent, fontSize: 12,
                fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.green.withOpacity(0.08),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: Colors.greenAccent.withOpacity(0.3)),
            ),
            child: Text(displayText,
                style: const TextStyle(color: Colors.greenAccent, fontSize: 22,
                    fontWeight: FontWeight.bold, height: 1.4)),
          ),

          // ── Status message ───────────────────────────────────────────────
          if (statusMsg.isNotEmpty) ...[
            const SizedBox(height: 14),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.orange.withOpacity(0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.orange.withOpacity(0.3)),
              ),
              child: Row(children: [
                const Icon(Icons.info_outline, color: Colors.orange, size: 16),
                const SizedBox(width: 8),
                Expanded(child: Text(statusMsg,
                    style: const TextStyle(color: Colors.orange, fontSize: 13))),
              ]),
            ),
          ],

          const SizedBox(height: 20),

          // ── Setup steps ──────────────────────────────────────────────────
          if (!isRunning) Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
                color: const Color(0xFF111111),
                borderRadius: BorderRadius.circular(10)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Setup (one time)', style: TextStyle(
                  color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
              const SizedBox(height: 10),
              _step('1', 'Grant both permissions above'),
              _step('2', 'Tap START'),
              _step('3', 'Open any app — YouTube, VLC, Chrome, etc.'),
              _step('4', 'Play a video in any language'),
              _step('5', 'Enable Android Live Captions:\n    Volume button → CC icon'),
              _step('6', 'Translated captions appear as floating overlay ✅'),
            ]),
          ),

          const SizedBox(height: 20),

          // ── START / STOP ─────────────────────────────────────────────────
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: isRunning ? _stop : _start,
              icon: Icon(isRunning
                  ? Icons.stop_circle_outlined
                  : Icons.play_circle_outline, size: 26),
              label: Text(
                isRunning ? 'STOP' : 'START LIVE TRANSLATION',
                style: const TextStyle(fontSize: 16,
                    fontWeight: FontWeight.bold, letterSpacing: 0.5),
              ),
              style: ElevatedButton.styleFrom(
                backgroundColor: isRunning
                    ? const Color(0xFF333333)
                    : const Color(0xFFFF3B3B),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 18),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14)),
                elevation: 4,
              ),
            ),
          ),
          const SizedBox(height: 8),
          const Center(child: Text(
            'Overlay stays on screen while you use other apps',
            style: TextStyle(color: Colors.white24, fontSize: 11))),
        ]),
      )),
    );
  }

  Widget _chip(String label) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
    decoration: BoxDecoration(
      color: Colors.white.withOpacity(0.06),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: Colors.white12),
    ),
    child: Text(label, style: const TextStyle(color: Colors.white60, fontSize: 12)),
  );

  Widget _langBtn(String label, String value) {
    final selected = targetLang == value;
    return GestureDetector(
      onTap: () => _setLanguage(value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
        decoration: BoxDecoration(
          color: selected ? const Color(0xFFFF3B3B) : const Color(0xFF1E1E1E),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(
              color: selected ? const Color(0xFFFF3B3B) : Colors.white12),
        ),
        child: Text(label, style: TextStyle(
            color: selected ? Colors.white : Colors.white54,
            fontWeight: selected ? FontWeight.bold : FontWeight.normal,
            fontSize: 14)),
      ),
    );
  }

  Widget _step(String n, String text) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(
        width: 20, height: 20,
        decoration: const BoxDecoration(
            color: Color(0xFFFF3B3B), shape: BoxShape.circle),
        child: Center(child: Text(n, style: const TextStyle(
            color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold))),
      ),
      const SizedBox(width: 10),
      Expanded(child: Text(text, style: const TextStyle(
          color: Colors.white54, fontSize: 12, height: 1.5))),
    ]),
  );

  Widget _permRow(IconData icon, String label, String desc,
      bool granted, VoidCallback onTap) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFF111111),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
            color: granted
                ? Colors.greenAccent.withOpacity(0.3)
                : Colors.white12),
      ),
      child: Row(children: [
        Icon(granted ? Icons.check_circle : Icons.radio_button_unchecked,
            color: granted ? Colors.greenAccent : Colors.white30, size: 20),
        const SizedBox(width: 10),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
            children: [
          Text(label, style: const TextStyle(color: Colors.white,
              fontSize: 13, fontWeight: FontWeight.w500)),
          Text(desc, style: const TextStyle(
              color: Colors.white38, fontSize: 11)),
        ])),
        if (!granted) GestureDetector(
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
                color: const Color(0xFFFF3B3B),
                borderRadius: BorderRadius.circular(6)),
            child: const Text('Allow', style: TextStyle(
                color: Colors.white, fontSize: 12,
                fontWeight: FontWeight.bold)),
          ),
        ),
        if (granted) const Icon(Icons.check, color: Colors.greenAccent, size: 16),
      ]),
    );
  }
}
