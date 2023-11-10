import 'package:flutter/material.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;
import 'package:url_launcher/url_launcher.dart';

class OnlineRecognition extends StatefulWidget {
  const OnlineRecognition({super.key});

  @override
  OnlineRecognitionState createState() => OnlineRecognitionState();
}

class OnlineRecognitionState extends State<OnlineRecognition> {
  stt.SpeechToText speech = stt.SpeechToText();

  @override
  void initState() {
    super.initState();
    _initializeSpeechRecognition();
  }

  void _initializeSpeechRecognition() async {
    bool available = await speech.initialize(
      onStatus: (status) {
        print('Speech recognition status: $status');
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
          _processCommand(result.recognizedWords);
        });
      },
    );
  }

  void _processCommand(String command) {
    if (command.contains('call')) {
      String contactName = _extractContactName(command);
      _makeCall(contactName);
    } else if (command.contains('navigate to')) {
      String location = _extractLocation(command);
      _openNavigation(location);
    }
    // Add more command processing logic as needed
  }

  String _extractContactName(String command) {
    // Implement logic to extract contact name
    // For simplicity, we'll assume the name follows 'call'
    return command.split('call').last.trim();
  }

  String _extractLocation(String command) {
    // Implement logic to extract location
    // For simplicity, we'll assume the location follows 'navigate to'
    return command.split('navigate to').last.trim();
  }

  void _makeCall(String contactName) async {
    // Replace this with your actual logic to initiate a call
    String phoneNumber =
        ''; // Add logic to get the phone number for the contact
    String telUri = 'tel:$phoneNumber';
    if (await canLaunch(telUri)) {
      await launch(telUri);
    } else {
      print('Error launching call');
    }
  }

  void _openNavigation(String location) {
    // Replace this with your actual logic to open navigation
    print('Opening navigation to $location');
    // You can use plugins or native platform code to open navigation
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Voice Control App'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            ElevatedButton(
              onPressed: _startListening,
              child: Text('Start Listening'),
            ),
          ],
        ),
      ),
    );
  }
}
