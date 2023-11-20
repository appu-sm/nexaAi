import 'package:fuzzywuzzy/fuzzywuzzy.dart';
import 'package:fuzzywuzzy/model/extracted_result.dart';

class SearchService {
  String? partialMatch(List choices, String searchText) {
    try {
// Using LevenshteinMatch
      ExtractedResult<Match> topMatch = extractOne(
          query: searchText,
          choices: choices.map((e) => Match(e)).toList(),
          cutoff: 60,
          getter: (x) => x.name);
      return topMatch.choice.name;
    } catch (e) {
      return null;
    }
  }
}

class Match {
  final String name;
  Match(this.name);
}
