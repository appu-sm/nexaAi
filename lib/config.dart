class Config {
  Config._();
//nexa_ai
  static const String appName = "NexaAI";
//offline_engine
  static const String offlineModel =
      "assets/models/vosk-model-small-en-in-0.4.zip";
  static const int sampleRate = 16000;
  static const int listenDuration = 5;
//map activity
  static const String geoLocation = "assets/geoLocation.json";
  static List commands = [
    "call", //  0
    "save this call", //  1
    "navigate to", //  2
    "launch", //  3
    "open", //  4
    "volume", //  5
    "mute", //  6
    "unmute", //  7
    "play", //  8
    "track", //  9
  ];
  static const Map<String, String> dialog = {
    "status": "Nexa status",
    "online": "online",
    "offline": "offline",
    "always_active": "Assistant always active",
    "speech_init_error": "Error initializing speech recognition {{value}}",
    "speech_error": "Speech recognition error {{value}}",
    "app_error": "{{value}} not found in installed apps list",
    "volume_error": "Sorry, unable to change volume {{value}}",
    "call_error": "unable to make call",
    "contact_error": "Sorry contact not found",
    "contact_save": "Contact saved as {{value}}",
    "retrive_error": "Sorry unable to retrieve the last incoming call",
    "no_permission":
        "Following permissions are not granted, which are crusial for the application to work properly {{value}}",
    "permission_text": "Open settings and provide the necessary permissions",
    "unified_engine": "Use unified engine",
    "engine": "Currently active engine",
    "unified": "Unified",
    "seperate": "Seperate",
    "no_map": "No maps installed in the device",
    "map_error": "Unable to navigate to {{value}}"
  };
}
