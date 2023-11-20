import 'package:flutter/material.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:nexa/offline_engine.dart';
import 'package:nexa/online_engine.dart';
import 'package:nexa/recognize_command.dart';
import 'package:nexa/services/background_service.dart';

class NexaAi extends StatefulWidget {
  const NexaAi({super.key});

  @override
  NexaAiState createState() => NexaAiState();
}

class NexaAiState extends State<NexaAi> {
  ConnectivityResult _connectivityResult = ConnectivityResult.none;
  String command = "";
  bool alwaysActive = false;

  @override
  void initState() {
    super.initState();
    // Initialize the connectivity status when the widget is created
    _checkConnectivity();
    _initializeAlwaysActive();
    // Listen for changes in the connectivity status
    Connectivity().onConnectivityChanged.listen((result) {
      setState(() {
        _connectivityResult = result;
      });
    });
  }

  Future<void> _initializeAlwaysActive() async {
    bool status = await BackgroundService.status();
    setState(() {
      alwaysActive = status;
    });
  }

  Future<void> _checkConnectivity() async {
    ConnectivityResult result = await Connectivity().checkConnectivity();
    setState(() {
      _connectivityResult = result;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
        appBar: AppBar(
          backgroundColor: Colors.black87,
          title: const Center(child: Text('NexaAI')),
        ),
        backgroundColor: Colors.black87,
        body: Column(mainAxisAlignment: MainAxisAlignment.start, children: [
          Card(
              elevation: 2,
              color: Colors.white70,
              margin: const EdgeInsets.symmetric(vertical: 5, horizontal: 15),
              child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 15),
                  child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceAround,
                      children: [
                        const Text("Nexa status"),
                        Text(_connectivityResult != ConnectivityResult.none
                            ? 'Online'
                            : 'Offline'),
                      ]))),
          Card(
              elevation: 2,
              color: Colors.white70,
              margin: const EdgeInsets.symmetric(vertical: 5, horizontal: 15),
              child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    const Text("Assistant always active"),
                    Switch(
                        value: alwaysActive,
                        onChanged: (value) => {
                              setState(() {
                                alwaysActive = value;
                              }),
                              value == true
                                  ? BackgroundService.startService(false,
                                      (String cmd) {
                                      // Handle the updated command here
                                      setState(() {
                                        command = cmd;
                                      });
                                      RecognizeCommand()
                                          .processCommand(command);
                                    })
                                  : BackgroundService.stopService()
                            },
                        activeColor: Colors.black87,
                        inactiveThumbColor: Colors.black87,
                        inactiveTrackColor: Colors.red.shade200,
                        activeTrackColor: Colors.greenAccent.shade200)
                  ])),
          const SizedBox(height: 20),
          Ink(
              decoration: const ShapeDecoration(
                color: Colors.white, // Set your desired background color
                shape: CircleBorder(),
              ),
              child: IconButton(
                  icon: const Icon(Icons.mic,
                      color: Colors.black), // Set icon color
                  onPressed: () async {
//                    RecognizeCommand().processCommand("launch ditto");

                    if (_connectivityResult != ConnectivityResult.none) {
                      OnlineEngine onlineEngine = OnlineEngine(
                        onCommandChanged: (String cmd) {
                          // Handle the updated command here
                          setState(() {
                            command = cmd;
                          });
                          RecognizeCommand().processCommand(command);
                        },
                      );
                      onlineEngine.startListening();
                    } else {
                      final OfflineEngine recognitionService =
                          OfflineEngine(onCommandChanged: (String cmd) {
                        setState(() {
                          command = cmd;
                        });
                        RecognizeCommand().processCommand(command);
                      });
//                      await recognitionService.initializeRecognition();
                      await recognitionService.startStopRecognition();
                    }
                  })),
          const SizedBox(height: 10),
          Text(
            command,
            style: const TextStyle(color: Colors.white),
          )
        ]));
  }
}
