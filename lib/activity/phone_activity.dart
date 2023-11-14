import 'package:flutter_phone_direct_caller/flutter_phone_direct_caller.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:nexa/services/notification_service.dart';
import 'package:call_log/call_log.dart';

class PhoneActivity {
  void makeCall(String name) async {
    final numericRegex = RegExp(r'^\d+$');
    bool isNumber = numericRegex.hasMatch(name);
    String phoneNumber = name;
    if (!isNumber) {
      phoneNumber = await getPhoneNumberByName(name);
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

  Future<String> getPhoneNumberByName(String name) async {
    List contacts = await getContacts();
    for (Contact contact in contacts) {
      List<String> contactName = [
        contact.displayName.toLowerCase(),
        contact.name.first.toLowerCase(),
        contact.name.last.toLowerCase(),
        contact.name.middle.toLowerCase()
      ];
      List<String> searchResults =
          contactName.where((item) => item.contains(name)).toList();
      if (searchResults.isNotEmpty) {
        if (contact.phones.isNotEmpty) {
          return contact.phones.first.normalizedNumber;
        }
      }
    }
    return '';
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
}
