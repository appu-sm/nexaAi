import 'package:device_installed_apps/app_info.dart';
import 'package:device_installed_apps/device_installed_apps.dart';
import 'package:nexa/services/notification_service.dart';

class AppActivity {
  void launchApp(String appName) async {
    Map installedApps = await getInstalledApps();
    if (installedApps.keys.contains(appName)) {
      try {
        DeviceInstalledApps.launchApp(installedApps[appName].bundleId);
      } catch (e) {
        checkPartialMatchApps(installedApps, appName);
      }
    } else {
      Notify.error("$appName not found in installed apps list");
    }
  }

  Future<Map<String, AppInfo>> getInstalledApps() async {
    List<AppInfo> apps = await DeviceInstalledApps.getApps();
    Map<String, AppInfo> appInfoMap = {};
    for (AppInfo app in apps) {
      appInfoMap[app.name!.toLowerCase()] = app;
    }
    return appInfoMap;
  }

  void checkPartialMatchApps(Map appList, String app) {
    List partialMatches =
        appList.keys.where((key) => key.contains(app)).toList();
    partialMatches.sort((a, b) => b.length.compareTo(a.length));
    if (partialMatches.isNotEmpty) {
      try {
        DeviceInstalledApps.launchApp(appList[partialMatches[0]].bundleId);
      } catch (e) {
        Notify.error("$app not able to launch");
      }
    } else {
      Notify.error("$app not found");
    }
  }
}
