import 'package:flutter/material.dart';
import 'package:fluttertoast/fluttertoast.dart';

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
    return _toast(message, Colors.red);
  }

  static _toast(String message, Color bgColor) {
    return Fluttertoast.showToast(
        msg: message,
        toastLength: Toast.LENGTH_SHORT,
        gravity: ToastGravity.CENTER,
        timeInSecForIosWeb: 1,
        backgroundColor: bgColor,
        textColor: Colors.white,
        fontSize: 16.0);
  }
}
