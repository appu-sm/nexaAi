import 'dart:async';
import 'package:flutter/material.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:nexa/config.dart';
import 'package:nexa/offline_engine.dart';
import 'package:nexa/online_engine.dart';
import 'package:nexa/recognize_command.dart';
import 'package:nexa/services/background_service.dart';
import 'package:nexa/services/background_service_new.dart';
import 'package:nexa/services/notification_service.dart';

class NexaAi extends StatefulWidget {
  const NexaAi({super.key});

  @override
  NexaAiState createState() => NexaAiState();
}

class NexaAiState extends State<NexaAi> {
  late StreamSubscription<ConnectivityResult> _connectivitySubscription;
  ConnectivityResult _connectivityResult = ConnectivityResult.none;
  String command = "";
  bool alwaysActive = false;
  bool unifiedEngine = true;

  @override
  void initState() {
    super.initState();
// Initialize the connectivity status when the widget is created
    _checkConnectivity();
    _initializeAlwaysActive();
// Listen for changes in the connectivity status
    _connectivitySubscription =
        Connectivity().onConnectivityChanged.listen((result) {
      setState(() {
        _connectivityResult = result;
      });
    });
  }

  @override
  void dispose() {
    _connectivitySubscription.cancel();
    super.dispose();
  }

  Future<void> _initializeAlwaysActive() async {
    bool status = await BackgroundServiceNew.status();
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
          title: const Center(child: Text(Config.appName)),
        ),
        backgroundColor: Colors.black87,
        body: Column(mainAxisAlignment: MainAxisAlignment.start, children: [
// Status card
          Card(
              elevation: 2,
              color: Colors.white70,
              margin: const EdgeInsets.symmetric(vertical: 5, horizontal: 15),
              child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 15),
                  child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceAround,
                      children: [
                        Text(Config.dialog['status']!),
                        Text(
                            _connectivityResult != ConnectivityResult.none
                                ? Config.dialog['online']![0].toUpperCase() +
                                    Config.dialog['online']!.substring(1)
                                : Config.dialog['offline']![0].toUpperCase() +
                                    Config.dialog['offline']!.substring(1),
                            style: TextStyle(
                                color: _connectivityResult !=
                                        ConnectivityResult.none
                                    ? Colors.green
                                    : Colors.red)),
                      ]))),
// Engine status
          Card(
              elevation: 2,
              color: Colors.white70,
              margin: const EdgeInsets.symmetric(vertical: 5, horizontal: 15),
              child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 15),
                  child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceAround,
                      children: [
                        Text(Config.dialog['engine']!),
                        Text(
                            unifiedEngine
                                ? Config.dialog['unified']!
                                : Config.dialog['seperate']!,
                            style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color:
                                    unifiedEngine ? Colors.blue : Colors.cyan)),
                      ]))),
// Engine selection
          Card(
              elevation: 2,
              color: Colors.white70,
              margin: const EdgeInsets.symmetric(vertical: 5, horizontal: 15),
              child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    Text(Config.dialog['unified_engine']!),
                    Switch(
                        value: unifiedEngine,
                        onChanged: (value) => setState(() {
                              unifiedEngine = value;
                            }),
                        activeColor: Colors.black87,
                        inactiveThumbColor: Colors.black87,
                        inactiveTrackColor: Colors.cyan.shade300,
                        activeTrackColor: Colors.blue.shade200)
                  ])),
// Floating icon switch
          Card(
              elevation: 2,
              color: Colors.white70,
              margin: const EdgeInsets.symmetric(vertical: 5, horizontal: 15),
              child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    Text(Config.dialog['always_active']!),
                    Switch(
                        value: alwaysActive,
                        onChanged: (value) => {
                              setState(() {
                                alwaysActive = value;
                              }),
                              value == true
                                  ? BackgroundServiceNew.startService(
                                      _connectivityResult !=
                                          ConnectivityResult.none,
                                      unifiedEngine, (String cmd) {
// Handle the updated command here
                                      setState(() {
                                        command = cmd;
                                      });
                                      Notify.info(command);
                                      RecognizeCommand()
                                          .processCommand(command);
                                    })
                                  : BackgroundServiceNew.stopService()
                            },
                        activeColor: Colors.black87,
                        inactiveThumbColor: Colors.black87,
                        inactiveTrackColor: Colors.red.shade200,
                        activeTrackColor: Colors.greenAccent.shade200)
                  ])),
          const SizedBox(height: 20),
// Mic Button
          Ink(
              decoration: const ShapeDecoration(
                color: Colors.white,
                shape: CircleBorder(),
              ),
              child: IconButton(
                  icon: const Icon(Icons.mic, color: Colors.black),
                  onPressed: () async {
//                    RecognizeCommand().processCommand("navigate to vellore");
// Online engine

                    if (_connectivityResult != ConnectivityResult.none) {
                      OnlineEngine onlineEngine = OnlineEngine(
                        onCommandChanged: (String cmd) {
                          setState(() {
                            command = cmd;
                          });
                          RecognizeCommand().processCommand(command);
                        },
                      );
                      onlineEngine.startListening();
                    } else {
// Offline engine
                      final OfflineEngine recognitionService =
                          OfflineEngine(onCommandChanged: (String cmd) {
                        setState(() {
                          command = cmd;
                        });
                        RecognizeCommand().processCommand(command);
                      });
                      await recognitionService.startStopRecognition();
                    }
                  })),
          const SizedBox(height: 200),
// Command
          Visibility(
              visible: command != "",
              child: Column(children: [
                const Text(
                  "Recognized command :",
                  style: TextStyle(color: Colors.white),
                ),
                Text(
                  command,
                  style: const TextStyle(color: Colors.white),
                )
              ])),
        ]));
  }
}
