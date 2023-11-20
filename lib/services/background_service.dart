import 'package:dash_bubble/dash_bubble.dart';
import 'package:nexa/offline_engine.dart';
import 'package:nexa/online_engine.dart';

class BackgroundService {
  static Future<void> startService(
      bool online, Function(String) onCommandChanged) async {
    await DashBubble.instance.startBubble(
        bubbleOptions: BubbleOptions(bubbleIcon: online ? 'online' : 'offline'),
        onTapDown: (x, y) async {
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
        });
  }

  static Future<bool> stopService() {
    return DashBubble.instance.stopBubble();
  }

  static Future<bool> status() async {
    return await DashBubble.instance.isRunning();
  }
}
