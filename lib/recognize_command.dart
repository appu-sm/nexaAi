import 'package:nexa/activity/app_activity.dart';
import 'package:nexa/activity/music_activity.dart';
import 'package:nexa/activity/phone_activity.dart';
import 'package:nexa/activity/map_activity.dart';

class RecognizeCommand {
  void processCommand(String command) {
    print("GIVEN COMMAND");
    print(command);
// Call a number / contact
    if (command.startsWith('call')) {
      String contactName = _extractIntent('call', command)[0];
      PhoneActivity().makeCall(contactName);
// Save last incoming call
    } else if (command.startsWith('save last call')) {
      List contactName =
          _extractIntent('last call as', command, multiple: true);
      PhoneActivity().saveContact(contactName);
// Navigation
    } else if (command.startsWith('navigate to')) {
      String location = _extractIntent('navigate to', command)[0];
      MapActivity().navigateTo(location);
// Open apps
    } else if (command.contains('launch') || command.contains('open')) {
      command = command.replaceFirst('launch', 'open');
      String appName = _extractIntent('open', command)[0];
      AppActivity().launchApp(appName);
// Volume control
    } else if (command.contains('volume')) {
      String newVolume = _extractIntent('volume', command)[0];
      MusicActivity().changeVolume(newVolume);
// Music / video / radio
    } else if (command.startsWith('play')) {
      String newVolume = _extractIntent('play', command)[0];
      MusicActivity().changeVolume(newVolume);
// Music control pause / resume / previous track / next track
    } else if (command.startsWith('track')) {}
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
