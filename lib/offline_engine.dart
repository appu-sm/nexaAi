import 'dart:convert';
import 'dart:async';
import 'dart:io';
import 'package:nexa/config.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:vosk_flutter/vosk_flutter.dart';

class OfflineEngine {
  Function(String) onCommandChanged;
  static const modelAsset = Config.offlineModel;
  static const sampleRate = Config.sampleRate;

  final VoskFlutterPlugin _vosk = VoskFlutterPlugin.instance();
  final ModelLoader _modelLoader = ModelLoader();
  Model? _model;
  Recognizer? _recognizer;
  SpeechService? _speechService;
  StreamSubscription _partialSubscription =
      StreamController().stream.listen((event) {});
  bool _recognitionStarted = false;

  OfflineEngine({required this.onCommandChanged}) {
    initializeRecognition();
  }

  Future<void> initializeRecognition() async {
    if (_speechService != null) {
// SpeechService instance / model already exists, no need to initialize again
      return;
    }
    if (await _modelLoader.isModelAlreadyLoaded(modelAsset)) {
      return;
    }

    await _modelLoader.loadFromAssets(modelAsset).then((modelPath) async {
      _model = await _vosk.createModel(modelPath);
      _recognizer =
          await _vosk.createRecognizer(model: _model!, sampleRate: sampleRate);

      if (Platform.isAndroid) {
        _speechService = await _vosk.initSpeechService(_recognizer!);
// continuous listning
/*
        _partialSubscription = _speechService!.onPartial().listen((partial) {
          Map<String, dynamic> jsonMap = json.decode(partial);
          String partialResult = jsonMap['partial'];
          //if (!mounted) return;
          RecognizeCommand().processCommand(partialResult);
        });
        */
// on full result
        _speechService!.onResult().listen((event) {
          Map<String, dynamic> jsonMap = json.decode(event);
          String result = jsonMap['text'];
          onCommandChanged(result);
        }).onError((error) => Notify.error(Config.dialog['speech_error']!
            .replaceAll("{{value}}", error.toString())));

// Schedule stopping the service after ${Config.listenDuration} seconds
        Future.delayed(const Duration(seconds: Config.listenDuration), () {
          if (_recognitionStarted) {
            _partialSubscription.cancel();
            _speechService?.stop();
            _speechService?.dispose();
            _recognitionStarted = false;
          }
        }).onError((error, stackTrace) => Notify.error(Config
            .dialog['speech_init_error']!
            .replaceAll("{{value}}", error.toString())));
      }
    });
  }

  Future<void> startStopRecognition() async {
    if (_recognitionStarted) {
      await _speechService?.stop();
    } else {
      await _speechService?.start();
    }
    _recognitionStarted = !_recognitionStarted;
  }

  void dispose() {
    _partialSubscription.cancel();
    _speechService?.stop();
    _speechService?.dispose();
  }
}
