//import 'package:nexa/channel/music_channel.dart';
import 'package:nexa/config.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:volume_regulator/volume_regulator.dart';

class MusicActivity {
  void changeVolume(String type) async {
    try {
      int currentVolume = await VolumeRegulator.getVolume();
      int newVolume;
      if (type == "up") {
        newVolume = currentVolume + 10;
      } else if (type == "down") {
        newVolume = currentVolume - 10;
      } else if (type == "mute") {
// mute
        final SharedPreferences prefs = await SharedPreferences.getInstance();
        await prefs.setInt('currentVolume', currentVolume);
        newVolume = 0;
      } else {
// unmute
        final SharedPreferences prefs = await SharedPreferences.getInstance();
        final int? volume = prefs.getInt('currentVolume');
        newVolume = volume!;
      }
      VolumeRegulator.setVolume(newVolume);
    } catch (e) {
      Notify.error(
          Config.dialog["volume_error"]!.replaceAll("{{value}}", e.toString()));
    }
  }

  void launchMusic(String app) {
//    MusicService.playMusicInDefaultApp('');
  }
}
