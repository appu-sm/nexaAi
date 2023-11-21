import 'package:nexa/config.dart';
import 'package:nexa/recognize_command.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:speech_to_text/speech_to_text.dart';

class SpeechEngine {
  SpeechToText speech = SpeechToText();
  String command = "";

  SpeechEngine() {
    _initializeSpeechRecognition();
  }

  Future<void> _initializeSpeechRecognition() async {
    bool available = await speech.initialize(
      onError: (error) {
        Notify.error(Config.dialog['speech_error']!
            .replaceAll("{{value}}", error.toString()));
      },
    );

    if (!available) {
      Notify.error(
          Config.dialog['speech_init_error']!.replaceAll("{{value}}", ""));
    }
  }

  void startListening() {
    speech.listen(
      onResult: (result) {
// Process the recognized speech result
        command = result.recognizedWords;
        RecognizeCommand().processCommand(command);
      },
    ).onError((error, stackTrace) => Notify.error(error.toString()));
  }
}
