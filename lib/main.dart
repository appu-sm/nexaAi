import 'package:flutter/material.dart';
import 'package:nexa/services/permissions_serevice.dart';

void main() {
  runApp(const Nexa());
}

class Nexa extends StatelessWidget {
  const Nexa({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(home: PermissionCheckScreen());
  }
}
