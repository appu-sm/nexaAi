import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:map_launcher/map_launcher.dart';
import 'package:nexa/config.dart';
import 'package:nexa/services/notification_service.dart';

class MapActivity {
  String offlineGeoLocation = Config.geoLocation;
  void searchPlace(String name) async {}

  void navigateTo(String location) async {
    final List availableMaps = await MapLauncher.installedMaps;
    if (availableMaps.isEmpty) {
      Notify.error(Config.dialog['no_map']!);
    }
    Coords? destination = await getCoordinates(location);
    try {
      availableMaps.first.showDirections(destination: destination!);
    } catch (e) {
      Notify.error(
          Config.dialog['map_error']!.replaceAll("{{value}}", location));
    }
    /*
    await availableMaps.first.showMarker(
      coords: Coords(37.759392, -122.5107336),
      title: "Ocean Beach",
    );
    */
/*
    if (await MapLauncher.isMapAvailable(MapType.google)) {
      await MapLauncher.showMarker(
        mapType: MapType.google,
        coords: coords,
        title: title,
        description: description,
      );
    }
    */
  }

  Future<Coords?> getCoordinates(String location) async {
    final String geoLocations = await rootBundle.loadString(offlineGeoLocation);
    final geoLocation = jsonDecode(geoLocations);
    List place = geoLocation
        .where((entry) => entry["name"].toString().toLowerCase() == location)
        .toList();
    if (place.isNotEmpty) {
      return Coords(place.first['lat'], place.first['lon']);
    }
    return null;
  }
}
