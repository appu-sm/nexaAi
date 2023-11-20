import 'package:speech_to_text/speech_to_text.dart' as stt;

class OnlineEngine {
  final bool? offline;
  stt.SpeechToText speech = stt.SpeechToText();
  String command = "";
  Function(String) onCommandChanged;

  OnlineEngine({this.offline, required this.onCommandChanged}) {
    _initializeSpeechRecognition(); // Call the initialization in the constructor
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
        print('Speech recognition error: $error');
      },
    );

    if (available) {
      print('Speech recognition initialized');
      // Start listening after initialization is complete
      startListening();
    } else {
      print('Error initializing speech recognition');
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
