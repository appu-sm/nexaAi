import 'package:flutter/material.dart';
import 'package:nexa/RecognizeCommand.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;

class OnlineRecognition extends StatefulWidget {
  const OnlineRecognition({super.key});

  @override
  OnlineRecognitionState createState() => OnlineRecognitionState();
}

class OnlineRecognitionState extends State<OnlineRecognition> {
  stt.SpeechToText speech = stt.SpeechToText();
  String _command = "";
  @override
  void initState() {
    super.initState();
    _initializeSpeechRecognition();
  }

  void _initializeSpeechRecognition() async {
    bool available = await speech.initialize(
      onStatus: (status) {
        print('Speech recognition status: $status');
        print(status.runtimeType);
        if (status == 'done') {
          RecognizeCommand().processCommand(_command);
        }
      },
      onError: (error) {
        print('Speech recognition error: $error');
      },
    );

    if (available) {
      print('Speech recognition initialized');
    } else {
      print('Error initializing speech recognition');
    }
  }

  void _startListening() {
    speech.listen(
      onResult: (result) {
        setState(() {
          // Process the recognized speech result
          _command = result.recognizedWords;
        });
      },
    ).onError((error, stackTrace) => setState(() {
          // Processing error
          _command = error.toString();
        }));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('NexaAI'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text("Online recognition"),
            ElevatedButton(
              /*
              onPressed: () => {
                RecognizeCommand().processCommand("save this number as aravi")
              },
              */
              onPressed: _startListening,
              child: const Text('Start Listening'),
            ),
            Text(_command),
          ],
        ),
      ),
    );
  }
}
