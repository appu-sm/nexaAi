import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:map_launcher/map_launcher.dart';
import 'package:nexa/config.dart';

class MapActivity {
  String offlineGeoLocation = Config.geoLocation;
  void searchPlace(String name) async {}

  void navigateTo(String location) async {
    final availableMaps = await MapLauncher.installedMaps;
    print(availableMaps);
    Coords? destination = await getCoordinates(location);
    availableMaps.first.showDirections(destination: destination!);
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
    var place = geoLocation.where((entry) => entry["name"] == location);
    if (place.isNotEmpty) {
      return Coords(place['lat'], place['lon']);
    }
    return null;
    //return Coords(12.499622764352193, 78.56927158789178);
  }
}
