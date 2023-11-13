import 'package:device_installed_apps/app_info.dart';
import 'package:device_installed_apps/device_installed_apps.dart';
import 'package:nexa/services/notification_service.dart';

class AppActivity {
  void launchApp(String app) async {
    Map apps = await getInstalledApps();
    print(apps);
    if (apps.keys.contains(app)) {
      try {
        DeviceInstalledApps.launchApp(apps[app].bundleId);
      } catch (e) {
        Notify.error("$app not able to launch");
      }
    } else {
      Notify.error("$app not found in installed apps list");
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
}
