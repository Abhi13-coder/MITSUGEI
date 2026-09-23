package com.mitsugei.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Device TTS for pronunciation of results / selected text. */
class TtsHelper(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.getDefault()
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts?.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "mitsugei-tts")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
