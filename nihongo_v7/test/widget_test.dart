// nihongo_v7/test/widget_test.dart
//
// Minimal smoke test — verifies the app builds and the root widget renders
// without throwing. Add feature-specific tests alongside this file.

// FIX: removed unused 'package:flutter/material.dart' import (caused warning → exit 1)
import 'package:flutter_test/flutter_test.dart';
import 'package:nihongo_v7/main.dart';

void main() {
  testWidgets('App renders without crashing', (WidgetTester tester) async {
    // Build the root widget.
    await tester.pumpWidget(const CaptionTranslatorApp());

    // Let any async init settle (timers, futures).
    await tester.pump(const Duration(milliseconds: 100));

    // The app title should appear somewhere in the tree.
    expect(find.text('Caption Translator'), findsOneWidget);
  });

  testWidgets('HomePage shows permission cards', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionTranslatorApp());
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('Overlay Permission'),      findsOneWidget);
    expect(find.text('Accessibility Service'),   findsOneWidget);
  });

  testWidgets('Language chips are present', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionTranslatorApp());
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.textContaining('English'), findsWidgets);
    expect(find.textContaining('Hindi'),   findsWidgets);
  });
}
