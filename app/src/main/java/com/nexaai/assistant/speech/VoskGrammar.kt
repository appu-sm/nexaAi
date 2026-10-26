package com.nexaai.assistant.speech

import com.nexaai.assistant.data.CommandCatalogue
import com.nexaai.assistant.data.Configuration
import com.nexaai.assistant.data.ConfigurationValidator
import com.nexaai.assistant.data.normalizeAlias
import com.nexaai.assistant.domain.AllowedApp

class VoskGrammar private constructor(private val commands: Map<String, String?>) {
    val phrases: List<String> = commands.keys.toList()

    fun canonicalTranscript(transcript: String): String {
        val normalized = normalizeAlias(transcript)
        if ("[unk]" in normalized.split(' ')) return "[unk]"
        return if (commands.containsKey(normalized)) commands[normalized] ?: "[unk]" else normalized
    }

    companion object {
        private val digitWords = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        private val smallNumbers = digitWords + listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        private val numericToken = Regex("\\b[0-9]+(?:\\.[0-9]+)?\\b")

        fun build(configuration: Configuration, apps: List<AllowedApp>): VoskGrammar? {
            if (!configuration.settings.grammarEnabled) return null
            ConfigurationValidator.validate(configuration)
            val commands = linkedMapOf<String, String?>("[unk]" to "[unk]")
            var bytes = 0
            fun add(spoken: String, canonical: String) {
                val phrase = normalizeAlias(spoken)
                if (commands.containsKey(phrase)) {
                    if (commands[phrase] != canonical) commands[phrase] = null
                } else {
                    bytes += phrase.toByteArray(Charsets.UTF_8).size + 3
                    require(commands.size < 65_536 && bytes <= 4 * 1024 * 1024) {
                        "Generated Vosk grammar is too large. Reduce command aliases or turn grammar mode off."
                    }
                    commands[phrase] = canonical
                }
            }
            fun addLiteral(phrase: String) {
                add(numericToken.replace(phrase) { spokenNumbers(it.value).first() }, phrase)
            }
            configuration.commands.filter { it.enabled }.forEach { command ->
                command.aliases.forEach { alias ->
                    when (val placeholder = CommandCatalogue.spec(command.id).placeholder) {
                        null -> addLiteral(alias)
                        "{app}" -> apps.flatMap { it.aliases }.forEach { addLiteral(alias.replace(placeholder, it)) }
                        "{percent}" -> (0..100).forEach { percent ->
                            spokenNumbers(percent.toString()).forEach { spoken ->
                                add(alias.replace(placeholder, spoken), alias.replace(placeholder, percent.toString()))
                            }
                        }
                        "{frequency}" -> (8750..10800).forEach { frequency ->
                            val value = java.math.BigDecimal(frequency).movePointLeft(2).stripTrailingZeros().toPlainString()
                            spokenNumbers(value).forEach { spoken ->
                                add(alias.replace(placeholder, spoken), alias.replace(placeholder, value))
                            }
                        }
                    }
                }
            }
            configuration.settings.grammarCustomWords.forEach { phrase ->
                if (!commands.containsKey(phrase)) add(phrase, phrase)
            }
            return VoskGrammar(commands)
        }

        private fun spokenNumbers(value: String): List<String> {
            val whole = value.substringBefore('.')
            val number = whole.toIntOrNull()
            val cardinal = if (number != null && number in 0..108) cardinal(number) else spellDigits(whole)
            val fraction = if ('.' in value) " point ${spellDigits(value.substringAfter('.'))}" else ""
            return listOf(cardinal + fraction, spellDigits(whole) + fraction).distinct()
        }

        private fun spellDigits(value: String) = value.map { digitWords[it.digitToInt()] }.joinToString(" ")

        private fun cardinal(number: Int): String = when {
            number < 20 -> smallNumbers[number]
            number < 100 -> tens[number / 10] + if (number % 10 == 0) "" else " ${digitWords[number % 10]}"
            number == 100 -> "one hundred"
            else -> "one hundred ${smallNumbers[number - 100]}"
        }
    }
}