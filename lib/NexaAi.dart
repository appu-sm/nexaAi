import 'package:flutter/material.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:nexa/OfflineRecognition.dart';
import 'package:nexa/OnlineRecognition.dart';

class NexaAi extends StatefulWidget {
  const NexaAi({super.key});

  @override
  NexaAiState createState() => NexaAiState();
}

class NexaAiState extends State<NexaAi> {
  ConnectivityResult _connectivityResult = ConnectivityResult.none;
  String command = "";
  dynamic Engine;

  @override
  void initState() {
    super.initState();
    // Initialize the connectivity status when the widget is created
    _checkConnectivity();
    // Listen for changes in the connectivity status
    Connectivity().onConnectivityChanged.listen((result) {
      setState(() {
        _connectivityResult = result;
      });
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
    /*
    // Based on connectivity status, return the appropriate widget
    Widget homeWidget;
    switch (_connectivityResult) {
      case ConnectivityResult.mobile:
      case ConnectivityResult.wifi:
        homeWidget = const OnlineRecognition();
        break;
      case ConnectivityResult.none:
      default:
        homeWidget = const OfflineRecognition();
        break;
    }

    return Scaffold(
      body: homeWidget,
    );
    */
    switch (_connectivityResult) {
      case ConnectivityResult.mobile:
      case ConnectivityResult.wifi:
        Engine = const OnlineRecognition();
        break;
      case ConnectivityResult.none:
      default:
        Engine = const OfflineRecognition();
        break;
    }

    return Scaffold(
      appBar: AppBar(
        title: Text('Voice Control App'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            ElevatedButton(
              onPressed: Engine._startListening,
              child: Text('Start Listening'),
            ),
            Text(command),
          ],
        ),
      ),
    );
  }
}
