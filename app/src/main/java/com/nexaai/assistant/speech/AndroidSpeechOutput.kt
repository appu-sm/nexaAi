package com.nexaai.assistant.speech

import android.content.Context
import com.nexaai.assistant.diagnostics.VoiceDiagnostics
import com.nexaai.assistant.diagnostics.VoiceEvent
import com.nexaai.assistant.diagnostics.DiagnosticCode
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechOutput(context: Context, private val diagnostics: VoiceDiagnostics = VoiceDiagnostics()) : SpeechOutput, TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Initializing)
    override val state: StateFlow<SpeechOutputState> = mutableState.asStateFlow()
    @Volatile private var engine: TextToSpeech? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    @Volatile private var pending: CompletableDeferred<Unit>? = null
    @Volatile private var pendingId: String? = null

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
        current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { diagnostics.record(VoiceEvent.TTS_START) }
            override fun onDone(utteranceId: String) {
                diagnostics.record(VoiceEvent.TTS_COMPLETE)
                if (utteranceId == pendingId) pending?.complete(Unit)
            }
            @Deprecated("Android callback")
            override fun onError(utteranceId: String) {
                diagnostics.record(VoiceEvent.ERROR, DiagnosticCode.TTS)
                if (utteranceId == pendingId) pending?.completeExceptionally(IOException("Offline speech playback failed."))
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                diagnostics.record(VoiceEvent.TTS_STOP)
                if (utteranceId == pendingId) pending?.completeExceptionally(IOException("Speech playback was interrupted."))
            }
        })
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
        main.post {
            engine?.stop()
            pending?.completeExceptionally(IOException("Speech playback stopped."))
        }
    }

    override suspend fun stopAndAwait() = withContext(Dispatchers.Main.immediate) {
        engine?.stop()
        pending?.completeExceptionally(IOException("Speech playback stopped."))
        Unit
    }

    override suspend fun speakAndAwait(text: String) = withContext(Dispatchers.Main.immediate) {
        val completion = CompletableDeferred<Unit>()
        val utterance = "nexaai-${System.nanoTime()}"
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) {
                    engine?.stop()
                    completion.completeExceptionally(IOException("Speech audio focus was interrupted."))
                }
            }.build()
        try {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw IOException("Speech audio focus unavailable. Hands-free paused; use push-to-talk.")
            }
            pending = completion
            pendingId = utterance
            if (state.value != SpeechOutputState.Ready ||
                engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) {
                throw IOException("Offline speech playback unavailable.")
            }
            completion.await()
        } finally {
            withContext(NonCancellable) {
                engine?.stop()
                pending = null
                pendingId = null
                audioManager.abandonAudioFocusRequest(focus)
            }
        }
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