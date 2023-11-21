import 'package:nexa/activity/app_activity.dart';
import 'package:nexa/activity/music_activity.dart';
import 'package:nexa/activity/phone_activity.dart';
import 'package:nexa/activity/map_activity.dart';
import 'package:nexa/config.dart';

class RecognizeCommand {
  List option = Config.commands;
  void processCommand(String command) {
// Call a number / contact
    if (command.startsWith(option[0])) {
      String contactName = _extractIntent('call', command)[0];
      PhoneActivity().makeCall(contactName);

// Save last incoming call
    } else if (command.startsWith(option[1])) {
      List contactName = _extractIntent('call as', command, multiple: true);
      PhoneActivity().saveContact(contactName);

// Navigation
    } else if (command.startsWith(option[2])) {
      String location = _extractIntent('navigate to', command)[0];
      MapActivity().navigateTo(location);

// Open apps
    } else if (command.startsWith(option[3]) || command.startsWith(option[4])) {
      command = command.replaceFirst('launch', 'open');
      String appName = _extractIntent('open', command)[0];
      AppActivity().launchApp(appName);

// Volume control
    } else if (command.startsWith(option[5])) {
      String newVolume = _extractIntent('volume', command)[0];
      MusicActivity().changeVolume(newVolume);

// Mute volume
    } else if (command.startsWith(option[6])) {
      MusicActivity().changeVolume("mute");

// Unmute volume
    } else if (command.startsWith(option[7])) {
      MusicActivity().changeVolume("unmute");

// Music / video / radio
    } else if (command.startsWith(option[8])) {
      String newVolume = _extractIntent('play', command)[0];
      MusicActivity().launchMusic(newVolume);

// Music control pause / resume / previous track / next track
    } else if (command.endsWith(option[9])) {}
  }

  List<String> _extractIntent(String activity, String command,
      {bool multiple = false}) {
    List<String> intents = [command.split(activity).last.trim().toLowerCase()];
    if (multiple) {
      intents = intents[0].split(' ');
    }
    return intents;
  }
}
