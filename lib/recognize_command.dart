import 'package:nexa/activity/app_activity.dart';
import 'package:nexa/activity/phone_activity.dart';
import 'package:nexa/activity/map_activity.dart';

class RecognizeCommand {
  void processCommand(String command) {
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
      String appName = _extractIntent('open', command)[0];
      AppActivity().launchApp(appName);
      // Volume control
    } else if (command.startsWith('volume')) {
      // Music / video / radio
    } else if (command.startsWith('play')) {
      // Music control
    } else if (command.startsWith('track')) {
      // go to next / previous track
    }
  }

  List<String> _extractIntent(String activity, String command,
      {bool multiple = false}) {
    List<String> intents = [command.split(activity).last.trim()];
    if (multiple) {
      intents = intents[0].split(' ');
    }
    return intents;
  }
}
