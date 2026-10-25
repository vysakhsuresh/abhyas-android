package com.layerbit.abhyas.ui.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
 *
 * The engine is started on the first tap of "Listen", not when the study screen opens. Binding it
 * starts a whole separate process, and most sittings never ask for speech - paying that on every
 * study screen would cost memory and battery for a feature that is usually unused.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    /**
     * Whether any speech engine is installed. A package query, which is cheap and does not start the
     * engine - it is what decides whether "Listen" is offered at all.
     */
    val isAvailable: Boolean = runCatching {
        appContext.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .isNotEmpty()
    }.getOrDefault(false)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, ScriptOption>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        pending?.let { (text, script) -> say(text, script) }
        pending = null
    }

    fun speak(text: String, script: ScriptOption) {
        if (!isAvailable || text.isBlank()) return
        if (ready) {
            say(text, script)
            return
        }
        // Not started yet, or still starting: remember the request and say it once it is ready.
        pending = text to script
        if (tts == null) tts = runCatching { TextToSpeech(appContext, this) }.getOrNull()
    }

    private fun say(text: String, script: ScriptOption) {
        val engine = tts ?: return
        runCatching {
            val voice = offlineVoiceFor(engine, localeFor(script)) ?: return
            engine.voice = voice
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
                putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        }
    }

    fun stop() {
        pending = null
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        pending = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }

    /**
     * The best installed voice for [locale] that works with no network. Same language required,
     * same country preferred - an en-GB voice reading an Indian student's English deck is fine; a
     * French voice reading it is not.
     */
    private fun offlineVoiceFor(engine: TextToSpeech, locale: Locale): Voice? {
        val offline = engine.voices.orEmpty().filter { voice ->
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
