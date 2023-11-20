import 'package:nexa/config.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;

class OnlineEngine {
  final bool? offline;
  stt.SpeechToText speech = stt.SpeechToText();
  String command = "";
  Function(String) onCommandChanged;

  OnlineEngine({this.offline, required this.onCommandChanged}) {
    _initializeSpeechRecognition();
  }

  Future<void> _initializeSpeechRecognition() async {
    bool available = await speech.initialize(
      onStatus: (status) {
        if (status == 'done') {
/* new commands are not recognized */
          //     RecognizeCommand().processCommand(command);
          //    command = '';
        }
      },
      onError: (error) {
        Notify.error(Config.dialog['speech_error']!
            .replaceAll("{{value}}", error.toString()));
      },
    );

    if (available) {
// Start listening after initialization is complete
      startListening();
    } else {
      Notify.error(
          Config.dialog['speech_init_error']!.replaceAll("{{value}}", ""));
    }
  }

  void startListening() {
    speech
        .listen(
          onDevice: offline,
          onResult: (result) {
            // Process the recognized speech result
            command = result.recognizedWords;
            onCommandChanged(command);
          },
        )
        .onError((error, stackTrace) => command = error.toString());
  }
}
