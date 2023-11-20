import 'package:flutter/material.dart';
import 'package:nexa/config.dart';
import 'package:nexa/nexa_ai.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:permission_handler/permission_handler.dart';

class PermissionCheckScreen extends StatelessWidget {
  final Future<bool> _permissionCheckFuture = _checkPermissions();

  PermissionCheckScreen({super.key});

  static Future<bool> _checkPermissions() async {
    List<Permission> requiredPermissions = [
      Permission.contacts,
      Permission.bluetooth,
      Permission.locationWhenInUse,
      Permission.microphone,
      Permission.phone,
      Permission.systemAlertWindow,
      //Permission.notification,
    ];

    Map<Permission, PermissionStatus> permissionStatus =
        await PermissionUtils.requestPermissions(requiredPermissions);
    var notGrantedPermissions = permissionStatus.entries
        .where((entry) => entry.value != PermissionStatus.granted);
    if (notGrantedPermissions.isNotEmpty) {
      Notify.warning(Config.dialog["no_permission"]!
          .replaceAll("{{value}}", notGrantedPermissions.join(", ")));
    }

    return await PermissionUtils.arePermissionsGranted(requiredPermissions);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: FutureBuilder<bool>(
          future: _permissionCheckFuture,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
// While waiting for the permission check, show a loading indicator.
              return const CircularProgressIndicator();
            } else if (snapshot.hasError) {
// Handle error case
              return Text('Error: ${snapshot.error}');
            } else if (snapshot.data == true) {
              return const NexaAi();
            } else {
// Permissions are not granted, show a message or take appropriate action
              return ElevatedButton(
                onPressed: () async {
                  // Open app settings
                  await PermissionUtils.openAppSettings();
                },
                child: Text(Config.dialog["permission_text"]!),
              );
            }
          },
        ),
      ),
    );
  }
}

class PermissionUtils {
  // Method to request multiple permissions
  static Future<Map<Permission, PermissionStatus>> requestPermissions(
      List<Permission> permissions) async {
    return await permissions.request();
  }

  // Method to check if a single permission is granted
  static Future<bool> isPermissionGranted(Permission permission) async {
    return await permission.isGranted;
  }

  // Method to check if all permissions in the list are granted
  static Future<bool> arePermissionsGranted(
      List<Permission> permissions) async {
    for (var permission in permissions) {
      if (!(await permission.isGranted)) {
        return false;
      }
    }
    return true;
  }

  // Method to open app settings
  static Future<void> openAppSettings() async {
    await openAppSettings();
  }
}
