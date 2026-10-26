package com.nexaai.assistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechOutput(context: Context) : SpeechOutput, TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Initializing)
    override val state: StateFlow<SpeechOutputState> = mutableState.asStateFlow()
    @Volatile private var engine: TextToSpeech? = null

    init {
        try {
            engine = TextToSpeech(context.applicationContext, this)
        } catch (failure: RuntimeException) {
            mutableState.value = SpeechOutputState.Unavailable("Text-to-speech could not be initialized.")
        }
    }

    override fun onInit(status: Int) {
        val current = engine
        if (status != TextToSpeech.SUCCESS || current == null) {
            mutableState.value = SpeechOutputState.Unavailable("No working text-to-speech engine is installed.")
            return
        }
        val offlineVoice = current.voices.orEmpty()
            .filter { voice -> voice.locale.language == "en" && isOfflineVoice(voice) }
            .sortedWith(compareByDescending<Voice> { it.locale.country == "US" }.thenByDescending { it.quality })
            .firstOrNull()
        if (offlineVoice == null || current.isLanguageAvailable(offlineVoice.locale) < TextToSpeech.LANG_AVAILABLE) {
            mutableState.value = SpeechOutputState.Unavailable("Install an English offline voice in Android text-to-speech settings.")
            return
        }
        if (current.setVoice(offlineVoice) == TextToSpeech.SUCCESS) mutableState.value = SpeechOutputState.Ready
        else mutableState.value = SpeechOutputState.Unavailable("The installed English voice could not be selected.")
    }

    override fun speak(text: String) {
        main.post {
            if (mutableState.value == SpeechOutputState.Ready) {
                engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            }
        }
    }

    override fun stop() {
        main.post { engine?.stop() }
    }

    override fun close() {
        main.post {
            engine?.stop()
            engine?.shutdown()
            engine = null
        }
    }

    private fun isOfflineVoice(voice: Voice): Boolean =
        !voice.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features

    private companion object {
        const val UTTERANCE_ID = "nexaai-response"
    }
}