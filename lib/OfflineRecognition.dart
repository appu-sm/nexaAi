import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:vosk_flutter/vosk_flutter.dart';

class OfflineRecognition extends StatefulWidget {
  const OfflineRecognition({Key? key}) : super(key: key);

  @override
  State<OfflineRecognition> createState() => _OfflineRecognitionState();
}

class _OfflineRecognitionState extends State<OfflineRecognition> {
  static const _textStyle = TextStyle(fontSize: 30, color: Colors.black);
  static const modelAsset = 'assets/models/vosk-model-small-en-in-0.4.zip';
  static const _sampleRate = 16000;

  final _vosk = VoskFlutterPlugin.instance();
  final _modelLoader = ModelLoader();
  String? _error;
  Model? _model;
  Recognizer? _recognizer;
  SpeechService? _speechService;
  String? command;
  bool _recognitionStarted = false;

  @override
  void initState() {
    super.initState();

    _modelLoader
        .loadFromAssets(modelAsset)
        .then((modelPath) => _vosk.createModel(modelPath))
        .then((model) => setState(() => _model = model))
        .then((_) =>
            _vosk.createRecognizer(model: _model!, sampleRate: _sampleRate))
        .then((value) => _recognizer = value)
        .then((recognizer) {
      if (Platform.isAndroid) {
        _vosk.initSpeechService(_recognizer!).then((speechService) {
          setState(() => _speechService = speechService);

          // Listen to the partial results stream
          _speechService!.onPartial().listen((partial) {
            // Parse the JSON string to extract the "partial" field
            Map<String, dynamic> jsonMap = json.decode(partial);
            String partialResult = jsonMap['partial'];
            setState(() {
              // Update the command variable with the partial result
              command = partialResult;
            });
          });
        }).catchError((e) {
          setState(() => _error = e.toString());
          return null;
        });
      }
    }).catchError((e) {
      setState(() => _error = e.toString());
      return null;
    });
  }

  @override
  void dispose() {
    // Dispose of the SpeechService instance when the widget is disposed
    _speechService?.stop();
    _speechService?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      return Scaffold(
          body: Center(child: Text("Error: $_error", style: _textStyle)));
    } else if (_model == null) {
      return const Scaffold(
          body: Center(child: Text("Loading model...", style: _textStyle)));
    } else if (Platform.isAndroid && _speechService == null) {
      return const Scaffold(
        body: Center(
          child: Text("Initializing speech service...", style: _textStyle),
        ),
      );
    } else {
      return Platform.isAndroid
          ? _mainInterface()
          : const Text("Not supported");
    }
  }

  Widget _mainInterface() {
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            ElevatedButton(
                onPressed: () async {
                  if (_recognitionStarted) {
                    await _speechService!.stop();
                    await _speechService?.dispose();
                  } else {
                    await _speechService!.start();
                  }
                  setState(() => _recognitionStarted = !_recognitionStarted);
                },
                child: Text(_recognitionStarted
                    ? "Stop recognition"
                    : "Start recognition")),
            Text(command ?? ""),
          ],
        ),
      ),
    );
  }
}
