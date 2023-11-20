import 'package:flutter_phone_direct_caller/flutter_phone_direct_caller.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:call_log/call_log.dart';
import 'package:nexa/services/search_service.dart';

class PhoneActivity {
  void makeCall(String name) async {
    final numericRegex = RegExp(r'^\d+$');
    bool isNumber = numericRegex.hasMatch(name);
    String phoneNumber = name;
    if (!isNumber) {
      phoneNumber = await getContactNumber(name);
    }

    if (phoneNumber != '') {
      bool? madeCall = await FlutterPhoneDirectCaller.callNumber(phoneNumber);
      if (!madeCall!) {
        Notify.warning("unable to make call");
      }
    } else {
      Notify.error("Sorry contact not found");
    }
  }

  Future<List> getContacts() async {
    List<Contact> contacts =
        await FlutterContacts.getContacts(withProperties: true);
    return contacts;
  }

  Future<String> getContactNumber(String name) async {
    List contacts = await getContacts();
    Map<String, String> contactNames = {};

    for (Contact contact in contacts) {
// combine first, last, middle and display names
      Set<String> uniqueNames = {};
      List<String> contactName = [
        if (contact.displayName.isNotEmpty &&
            uniqueNames.add(contact.displayName))
          contact.displayName.toLowerCase(),
        if (contact.name.first.isNotEmpty &&
            uniqueNames.add(contact.name.first))
          contact.name.first.toLowerCase(),
        if (contact.name.last.isNotEmpty && uniqueNames.add(contact.name.last))
          contact.name.last.toLowerCase(),
        if (contact.name.middle.isNotEmpty &&
            uniqueNames.add(contact.name.middle))
          contact.name.middle.toLowerCase(),
      ];

      List<String> searchResults =
          contactName.where((item) => item.contains(name)).toList();
      if (searchResults.isNotEmpty) {
        if (contact.phones.isNotEmpty) {
// returns the exact match
          return contact.phones.first.normalizedNumber;
        }
      } else {
        if (contact.phones.isNotEmpty) {
          contactNames[contact.phones.first.normalizedNumber] =
              contactName.join(" ");
        }
      }
    }
    String? matchedContactName =
        SearchService().partialMatch(contactNames.values.toList(), name);
    String number = (matchedContactName != null)
        ? getNumberByName(contactNames, matchedContactName)
        : "";
    return number;
  }

  void sendSms() {}
  void lastCalledStatus() {}

  Future<String?> getLastNumber() async {
    String? lastCalledNumber;
    Iterable<CallLogEntry> incomingCalls = await CallLog.query(
      type: CallType.incoming,
    ); // always returns latest record first
    if (incomingCalls.isNotEmpty) {
      lastCalledNumber = incomingCalls.take(1).first.number;
    }

    return lastCalledNumber;
  }

  void saveContact(List name) async {
    String? number = await getLastNumber();
    if (number != null) {
      final newContact = Contact()..name.first = name[0];
      if (name.length > 1) {
        newContact.name.last = name[1];
      }
      newContact.phones = [Phone(number)];
      await newContact.insert();
      Notify.success("Contact saved as ${name[0]}");
    } else {
      Notify.error("Sorry unable to retrieve the last incoming call");
    }
  }

  String getNumberByName(Map<String, String> map, String value) {
    for (var entry in map.entries) {
      if (entry.value == value) {
        return entry.key;
      }
    }
    return "";
  }
}
