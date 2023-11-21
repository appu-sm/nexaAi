import 'package:dash_bubble/dash_bubble.dart';
import 'package:nexa/config.dart';
import 'package:nexa/offline_engine.dart';
import 'package:nexa/online_engine.dart';
import 'package:nexa/speech_engine.dart';

class BackgroundServiceNew {
  static Future<void> startService(bool online, bool unifiedEngine,
      Function(String) onCommandChanged) async {
    await DashBubble.instance.startBubble(
        bubbleOptions: BubbleOptions(
            keepAliveWhenAppExit: true,
            bubbleIcon:
                online ? Config.dialog['online'] : Config.dialog['offline']),
        onTapDown: (x, y) async {
          if (unifiedEngine) {
            SpeechEngine speechEngine = SpeechEngine();
            speechEngine.startListening();
          } else {
            if (online) {
              OnlineEngine onlineEngine = OnlineEngine(
                onCommandChanged: (String cmd) {
// Handle the updated command here
                  onCommandChanged(cmd);
                },
              );
              onlineEngine.startListening();
            } else {
              final OfflineEngine recognitionService =
                  OfflineEngine(onCommandChanged: (String cmd) {
                onCommandChanged(cmd);
              });
              await recognitionService.initializeRecognition();
              await recognitionService.startStopRecognition();
            }
          }
        });
  }

  static Future<bool> stopService() {
    return DashBubble.instance.stopBubble();
  }

  static Future<bool> status() async {
    return await DashBubble.instance.isRunning();
  }
}
