package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.LyricTranslate
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.entity.Lyrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LyricsViewModel @Inject constructor() : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Loaded(
            val lyrics: Lyrics,
            val showTranslation: Boolean = false,
            val translations: List<String>? = null,
            val translationLanguage: String? = null,
            val sourceLanguage: String? = null,
            val translating: Boolean = false,
            val translationChecked: Boolean = false,
            val translationRequested: Boolean = false,
        ) : State()
        object NotFound : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var loadedKey: String? = null

    fun load(title: String, artist: String, album: String, durationSec: Int) {
        val key = "$title|$artist"
        if (loadedKey == key && _state.value !is State.NotFound) return
        loadedKey = key
        _state.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val lyrics = LyricsApi.fetch(title, artist, album, durationSec)
            withContext(Dispatchers.Main) {
                _state.value = if (lyrics == null || lyrics.isEmpty) State.NotFound
                else State.Loaded(
                    lyrics = lyrics,
                    sourceLanguage = lyrics.language,
                    translationChecked = lyrics.language != null,
                )
            }
        }
    }

    fun toggleTranslation(targetLang: String) {
        val cur = _state.value as? State.Loaded ?: return
        if (cur.showTranslation) {
            _state.value = cur.copy(showTranslation = false)
            return
        }
        if (cur.translationLanguage == targetLang) {
            _state.value = cur.copy(showTranslation = true)
            return
        }
        if (cur.translating) {
            _state.value = cur.copy(translationRequested = true)
            return
        }
        translate(cur, targetLang, showWhenDone = true)
    }

    fun prepareTranslation(targetLang: String) {
        val cur = _state.value as? State.Loaded ?: return
        if (cur.translating || cur.translationLanguage == targetLang) return
        if (sameLanguage(cur.sourceLanguage, targetLang)) return
        translate(cur, targetLang, showWhenDone = false)
    }

    private fun translate(cur: State.Loaded, targetLang: String, showWhenDone: Boolean) {
        _state.value = cur.copy(
            translating = true,
            translationRequested = showWhenDone,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val lines = cur.lyrics.lines.map { it.text }
            val detectedLanguage = cur.sourceLanguage
                ?: LyricTranslate.detectLanguage(lines, targetLang)
            if (detectedLanguage != null) {
                val matchesTarget = sameLanguage(detectedLanguage, targetLang)
                withContext(Dispatchers.Main) {
                    val latest = _state.value as? State.Loaded ?: return@withContext
                    if (latest.lyrics == cur.lyrics) {
                        _state.value = latest.copy(
                            translationChecked = true,
                            sourceLanguage = detectedLanguage,
                            translating = !matchesTarget,
                            translationRequested = latest.translationRequested && !matchesTarget,
                        )
                    }
                }
                if (matchesTarget) return@launch
            }
            val result = LyricTranslate.translateLines(lines, targetLang)
            withContext(Dispatchers.Main) {
                val latest = _state.value as? State.Loaded ?: return@withContext
                if (latest.lyrics != cur.lyrics) return@withContext
                if (result == null) {
                    _state.value = latest.copy(
                        translating = false,
                        translationChecked = true,
                        showTranslation = false,
                        translationRequested = false,
                    )
                    return@withContext
                }
                _state.value = latest.copy(
                    translating = false,
                    translationChecked = true,
                    showTranslation = latest.translationRequested &&
                        !sameLanguage(result.sourceLanguage, targetLang),
                    translationRequested = false,
                    translations = result.lines,
                    translationLanguage = targetLang,
                    sourceLanguage = result.sourceLanguage,
                )
            }
        }
    }

    private fun sameLanguage(source: String?, target: String): Boolean =
        source?.substringBefore('-')
            ?.equals(target.substringBefore('-'), ignoreCase = true) == true
}
