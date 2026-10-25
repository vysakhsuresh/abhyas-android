package com.layerbit.abhyas.ui.components

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.layerbit.abhyas.data.ocr.ScriptOption
import java.util.Locale

/**
 * Reads a card aloud with the phone's own speech engine, offline voices only.
 *
 * The engine is another app - Google's, Samsung's - and some of its voices are synthesised on a
 * server. Abhyas cannot open a socket itself, but handing card text to an engine that would is the
 * same leak by another route, so a network voice is never chosen: only voices that declare they
 * need no connection, and the request itself asks for local synthesis. A phone with no offline
 * voice for the language simply has the speak button do nothing rather than send the text away.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)

    /** Compose state, so the speak button appears only once there is an engine behind it. */
    var isReady by mutableStateOf(false)
        private set

    override fun onInit(status: Int) {
        isReady = status == TextToSpeech.SUCCESS
    }

    fun speak(text: String, script: ScriptOption) {
        if (!isReady || text.isBlank()) return
        runCatching {
            val locale = localeFor(script)
            val voice = offlineVoiceFor(locale) ?: return
            tts.voice = voice
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
                putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        }
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }

    /**
     * The best installed voice for [locale] that works with no network. Same language required,
     * same country preferred - an en-GB voice reading an Indian student's English deck is fine; a
     * French voice reading it is not.
     */
    private fun offlineVoiceFor(locale: Locale): Voice? {
        val offline = tts.voices.orEmpty().filter { voice ->
            !voice.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty() &&
                voice.locale.language == locale.language
        }
        return offline.maxWithOrNull(
            compareBy<Voice>({ it.locale.country == locale.country }, { it.quality }, { -it.latency })
        )
    }

    private fun localeFor(script: ScriptOption): Locale = when (script) {
        ScriptOption.DEVANAGARI -> Locale("hi", "IN")
        ScriptOption.CHINESE -> Locale.SIMPLIFIED_CHINESE
        ScriptOption.JAPANESE -> Locale.JAPANESE
        ScriptOption.KOREAN -> Locale.KOREAN
        ScriptOption.LATIN -> {
            // A Latin deck on a phone set to an Indic or CJK language is almost certainly English;
            // otherwise the phone's own language is the best guess at what the deck is in.
            val device = Locale.getDefault()
            if (device.language in NON_LATIN_LANGUAGES) Locale("en", "IN") else device
        }
    }

    private companion object {
        const val UTTERANCE_ID = "abhyas-card"
        val NON_LATIN_LANGUAGES = setOf("hi", "mr", "ne", "sa", "zh", "ja", "ko", "ta", "te", "bn")
    }
}

/** A [Speaker] tied to the composition: created once, shut down when the screen leaves. */
@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}
