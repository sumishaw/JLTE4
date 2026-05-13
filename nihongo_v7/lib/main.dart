import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const CaptionTranslatorApp());
}

class CaptionTranslatorApp extends StatelessWidget {
  const CaptionTranslatorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Caption Translator',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        colorScheme: const ColorScheme.dark(primary: Color(0xFFFF3B3B)),
        scaffoldBackgroundColor: const Color(0xFF0A0A0A),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const _channel = MethodChannel('overlay_channel');

  bool _overlayPermission  = false;
  bool _accessibilityOn    = false;
  bool _overlayRunning     = false;
  String _latestCaption    = 'Waiting for captions…';
  String _targetLang       = 'hindi';   // 'english' or 'hindi'

  Timer? _captionPoller;

  @override
  void initState() {
    super.initState();
    _checkPermissions();
    _startPoller();
  }

  @override
  void dispose() {
    _captionPoller?.cancel();
    super.dispose();
  }

  // ── permissions ───────────────────────────────────────────────────────────
  Future<void> _checkPermissions() async {
    final overlay       = await _channel.invokeMethod<bool>('hasOverlayPermission')      ?? false;
    final accessibility = await _channel.invokeMethod<bool>('checkAccessibilityEnabled') ?? false;
    if (mounted) {
      setState(() {
        _overlayPermission = overlay;
        _accessibilityOn   = accessibility;
      });
    }
  }

  Future<void> _requestOverlay() async {
    await _channel.invokeMethod('requestOverlayPermission');
    await Future.delayed(const Duration(seconds: 1));
    _checkPermissions();
  }

  Future<void> _openAccessibility() async {
    await _channel.invokeMethod('openAccessibilitySettings');
    await Future.delayed(const Duration(seconds: 1));
    _checkPermissions();
  }

  // ── overlay control ───────────────────────────────────────────────────────
  Future<void> _toggleOverlay() async {
    if (_overlayRunning) {
      await _channel.invokeMethod('stopOverlay');
      setState(() => _overlayRunning = false);
    } else {
      if (!_overlayPermission || !_accessibilityOn) {
        _showSnack('Grant overlay & accessibility permissions first.');
        return;
      }
      await _channel.invokeMethod('startOverlay');
      setState(() => _overlayRunning = true);
    }
  }

  Future<void> _setLanguage(String lang) async {
    await _channel.invokeMethod('setTargetLanguage', {'language': lang});
    setState(() => _targetLang = lang);
  }

  // ── caption polling ───────────────────────────────────────────────────────
  void _startPoller() {
    _captionPoller = Timer.periodic(const Duration(milliseconds: 300), (_) async {
      try {
        final text = await _channel.invokeMethod<String>('getLatestTranslation');
        if (text != null && text != _latestCaption && mounted) {
          setState(() => _latestCaption = text);
        }
      } catch (_) {}
    });
  }

  void _showSnack(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  // ── UI ────────────────────────────────────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(
                children: [
                  const Text('🌐', style: TextStyle(fontSize: 28)),
                  const SizedBox(width: 10),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: const [
                      Text('Caption Translator',
                          style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold,
                              color: Colors.white)),
                      Text('Live overlay · English & Hindi',
                          style: TextStyle(fontSize: 12, color: Colors.white54)),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 24),

              // Permission cards
              _PermCard(
                icon: Icons.layers,
                title: 'Overlay Permission',
                subtitle: 'Draw captions above all apps',
                granted: _overlayPermission,
                onTap: _requestOverlay,
              ),
              const SizedBox(height: 10),
              _PermCard(
                icon: Icons.accessibility_new,
                title: 'Accessibility Service',
                subtitle: 'Read captions from media apps',
                granted: _accessibilityOn,
                onTap: _openAccessibility,
              ),

              const SizedBox(height: 24),

              // Language selector
              Text('Translate to', style: TextStyle(color: Colors.white60, fontSize: 13)),
              const SizedBox(height: 8),
              Row(
                children: [
                  _LangChip(label: '🇬🇧 English', value: 'english',
                      selected: _targetLang == 'english', onTap: _setLanguage),
                  const SizedBox(width: 10),
                  _LangChip(label: '🇮🇳 Hindi', value: 'hindi',
                      selected: _targetLang == 'hindi',  onTap: _setLanguage),
                ],
              ),

              const SizedBox(height: 24),

              // Start / Stop button
              SizedBox(
                width: double.infinity,
                height: 52,
                child: ElevatedButton.icon(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _overlayRunning
                        ? const Color(0xFF3A0000)
                        : const Color(0xFFFF3B3B),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14)),
                  ),
                  icon: Icon(_overlayRunning ? Icons.stop : Icons.play_arrow),
                  label: Text(
                    _overlayRunning ? 'Stop Overlay' : 'Start Overlay',
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                  ),
                  onPressed: _toggleOverlay,
                ),
              ),

              const SizedBox(height: 24),

              // Live caption preview
              Expanded(
                child: Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: const Color(0xFF151515),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: Colors.white12),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Live Caption Preview',
                          style: TextStyle(
                              color: Colors.white38, fontSize: 11,
                              letterSpacing: 1.2)),
                      const SizedBox(height: 12),
                      Expanded(
                        child: Center(
                          child: Text(
                            _latestCaption,
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                                color: Colors.white,
                                fontSize: 18,
                                height: 1.5,
                                fontWeight: FontWeight.w500),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              const SizedBox(height: 12),
              Center(
                child: Text(
                  'Overlay is transparent & always on top',
                  style: TextStyle(color: Colors.white24, fontSize: 11),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── helper widgets ─────────────────────────────────────────────────────────

class _PermCard extends StatelessWidget {
  final IconData icon;
  final String title, subtitle;
  final bool granted;
  final VoidCallback onTap;

  const _PermCard({
    required this.icon, required this.title, required this.subtitle,
    required this.granted, required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0xFF151515),
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: granted ? null : onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              Icon(icon, color: granted ? Colors.greenAccent : Colors.white38, size: 22),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.w600,
                            fontSize: 14)),
                    Text(subtitle,
                        style: TextStyle(color: Colors.white38, fontSize: 12)),
                  ],
                ),
              ),
              granted
                  ? const Icon(Icons.check_circle, color: Colors.greenAccent, size: 20)
                  : const Icon(Icons.chevron_right, color: Colors.white38, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

class _LangChip extends StatelessWidget {
  final String label, value;
  final bool selected;
  final void Function(String) onTap;

  const _LangChip({
    required this.label, required this.value,
    required this.selected, required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => onTap(value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
        decoration: BoxDecoration(
          color: selected ? const Color(0xFFFF3B3B) : const Color(0xFF1E1E1E),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(
              color: selected ? const Color(0xFFFF3B3B) : Colors.white12),
        ),
        child: Text(label,
            style: TextStyle(
                color: selected ? Colors.white : Colors.white54,
                fontWeight: selected ? FontWeight.bold : FontWeight.normal,
                fontSize: 14)),
      ),
    );
  }
}
