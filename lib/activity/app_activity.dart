import 'package:device_installed_apps/app_info.dart';
import 'package:device_installed_apps/device_installed_apps.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:nexa/services/search_service.dart';

class AppActivity {
  void launchApp(String appName) async {
    Map installedApps = await getInstalledApps();
    if (installedApps.keys.contains(appName)) {
      try {
// Exact match
        DeviceInstalledApps.launchApp(installedApps[appName].bundleId);
      } catch (e) {
// Partial match
        checkPartialMatchApps(installedApps, appName);
      }
    } else {
// Misspelled name
      checkPartialMatchApps(installedApps, appName);
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
    String? matchedApp =
        SearchService().partialMatch(appList.keys.toList(), app);
    if (matchedApp != null) {
      DeviceInstalledApps.launchApp(appList[app].bundleId);
    } else {
      Notify.error("$app not found in installed apps list");
    }
  }
}
