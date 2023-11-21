import 'package:flutter/material.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:flutter_tts/flutter_tts.dart';

class Notify {
  static info(String message) {
    return _toast(message, Colors.blue);
  }

  static success(String message) {
    return _toast(message, Colors.green);
  }

  static warning(String message) {
    return _toast(message, Colors.orange);
  }

  static error(String message) {
    //_voiceOuput(message);
    return _toast(message, Colors.red);
  }

  static _toast(String message, Color bgColor) {
    return Fluttertoast.showToast(
        msg: message,
        toastLength: Toast.LENGTH_LONG,
        gravity: ToastGravity.CENTER,
        timeInSecForIosWeb: 1,
        backgroundColor: bgColor,
        textColor: Colors.white,
        fontSize: 16.0);
  }

  static _voiceOuput(String message) async {
    try {
      FlutterTts flutterTts = FlutterTts();
      await flutterTts.setLanguage("en-US");
      await flutterTts.setSpeechRate(1.0);
      await flutterTts.setVolume(1.0);
      await flutterTts.setPitch(1.0);
      await flutterTts.speak(message);
      await flutterTts.stop();
    } catch (e) {
      print(e);
    }
  }
}
