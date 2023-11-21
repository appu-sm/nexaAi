//import 'package:nexa/channel/music_channel.dart';
import 'package:nexa/config.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:volume_regulator/volume_regulator.dart';

class MusicActivity {
  void changeVolume(String type) async {
    try {
      int currentValue = await VolumeRegulator.getVolume();
      int newVolume;
      if (type == "up") {
        newVolume = currentValue + 2;
      } else if (type == "down") {
        newVolume = currentValue - 2;
      } else {
        newVolume = 0; // mute
      }
      VolumeRegulator.setVolume(newVolume);
    } catch (e) {
      Notify.error(
          Config.dialog["volume_error"]!.replaceAll("{{value}}", e.toString()));
    }
  }

  void launchMusic() {
//    MusicService.playMusicInDefaultApp('');
  }
}
