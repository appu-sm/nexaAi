import 'package:nexa/activity/PhoneActivity.dart';
import 'package:nexa/activity/mapActivity.dart';
import 'package:url_launcher/url_launcher_string.dart';
import 'package:flutter_phone_direct_caller/flutter_phone_direct_caller.dart';

class RecognizeCommand {
  void processCommand(String command) {
// Call a number / contact
    if (command.startsWith('call')) {
      String contactName = _extractIntent('call', command)[0];
      print("contactName");
      print(contactName);
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
      /* if (await canLaunchUrl(url)) {
      await launchUrl(url);
    } else {
      throw 'Could not launch $url';
    }
    */

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

  void _makeCall(String contactName) async {
    // Replace this with your actual logic to initiate a call
    String phoneNumber =
        '9884468850'; // Add logic to get the phone number for the contact
    String telUri = 'tel:$phoneNumber';
    bool? res = await FlutterPhoneDirectCaller.callNumber(phoneNumber);
    print(res);
    /*
    if (await canLaunchUrlString(telUri)) {
      await launchUrlString(telUri);
    } else {
      print('Error launching call');
    }
    */
  }
}
