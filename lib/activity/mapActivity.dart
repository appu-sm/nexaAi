import 'package:flutter/material.dart';
import 'package:flutter_phone_direct_caller/flutter_phone_direct_caller.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:map_launcher/map_launcher.dart';

class MapActivity {
  void searchPlace(String name) async {}

  void navigateTo(String location) async {
    final availableMaps = await MapLauncher.installedMaps;
    print(availableMaps);
    availableMaps.first.showDirections(
        destination: Coords(12.499622764352193, 78.56927158789178));
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
}
