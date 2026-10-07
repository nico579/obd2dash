package com.nico.obd2dash.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

internal enum class UpdateStage { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY }

internal data class AppUpdateState(
    val installedVersion: String,
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val message: String? = null,
    val hasAccessToken: Boolean = false
) {
    val busy: Boolean get() = stage in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)
}

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppUpdateRepository(application)
    private val _state = MutableStateFlow(AppUpdateState(repository.installed.versionName, hasAccessToken = repository.hasAccessToken))
    val state = _state.asStateFlow()
    private var operation: Job? = null

    fun checkIfNeeded() {
        if (_state.value.stage == UpdateStage.IDLE && _state.value.message == null) check()
    }

    fun check() {
        if (operation?.isCompleted == false) return
        operation = viewModelScope.launch {
            _state.update { it.copy(stage = UpdateStage.CHECKING, release = null, message = null, downloadedBytes = 0) }
            try {
                val release = withTimeout(45_000) { repository.latest() }
                val available = UpdateVersion.parse(release.versionName) > UpdateVersion.parse(_state.value.installedVersion)
                _state.update { it.copy(stage = if (available) UpdateStage.AVAILABLE else UpdateStage.CURRENT, release = release) }
            } catch (error: Exception) {
                recover(error, UpdateStage.IDLE)
            }
        }
    }

    fun download() {
        if (operation?.isCompleted == false) return
        val release = _state.value.release ?: return
        if (_state.value.stage !in setOf(UpdateStage.AVAILABLE, UpdateStage.READY)) return
        operation = viewModelScope.launch {
            _state.update { it.copy(stage = UpdateStage.DOWNLOADING, message = null, downloadedBytes = 0) }
            try {
                withTimeout(180_000) {
                    repository.download(release) { count ->
                        _state.update { it.copy(downloadedBytes = count) }
                    }
                }
                _state.update { it.copy(stage = UpdateStage.READY) }
            } catch (error: Exception) {
                recover(error, UpdateStage.AVAILABLE)
            }
        }
    }

    fun prepareInstall(onReady: (File) -> Unit) {
        if (operation?.isCompleted == false || _state.value.stage != UpdateStage.READY) return
        val release = _state.value.release ?: return
        operation = viewModelScope.launch {
            _state.update { it.copy(stage = UpdateStage.VERIFYING, message = null) }
            try {
                val file = repository.prepareInstall(release)
                _state.update { it.copy(stage = UpdateStage.READY) }
                onReady(file)
            } catch (error: Exception) {
                recover(error, UpdateStage.AVAILABLE)
            }
        }
    }

    fun installerMessage(message: String) { _state.update { it.copy(message = message) } }

    fun saveAccessToken(token: String) {
        if (operation?.isCompleted == false) return
        operation = viewModelScope.launch {
            try {
                _state.update { it.copy(stage = UpdateStage.CHECKING, message = null) }
                repository.saveAccessToken(token)
                _state.update { it.copy(hasAccessToken = repository.hasAccessToken, release = null, stage = UpdateStage.IDLE,
                    message = "Accès GitHub enregistré. Appuyez sur Vérifier maintenant.") }
            } catch (error: Exception) {
                recover(error, UpdateStage.IDLE)
            }
        }
    }

    fun cancel() {
        if (operation?.isCompleted == false) {
            _state.update { it.copy(message = "Annulation en cours…") }
            operation?.cancel()
        }
    }

    private fun recover(error: Exception, fallback: UpdateStage) {
        val message = when (error) {
            is TimeoutCancellationException -> "Le serveur ne répond pas assez vite. Réessayez."
            is CancellationException -> "Opération annulée."
            is UpdateHttpException -> when (error.status) {
                401 -> "Accès GitHub refusé. Vérifiez ou renouvelez le jeton dans Accès GitHub."
                403, 429 -> "Accès GitHub refusé ou vérifications temporairement limitées. Vérifiez les droits du jeton, puis réessayez plus tard."
                404 -> "Publication inaccessible. Pour ce dépôt privé, configurez un jeton dans Accès GitHub avec lecture du contenu de nico579/obd2dash."
                else -> "Le serveur est indisponible. Réessayez plus tard."
            }
            is IOException -> "Accès Internet indisponible ou téléchargement interrompu. Vérifiez le Wi-Fi ou les données mobiles, puis réessayez."
            is IllegalArgumentException -> error.message ?: "Fichier de mise à jour non valide."
            else -> "Impossible de vérifier la mise à jour. Réessayez."
        }
        _state.update { it.copy(stage = fallback, message = message) }
        if (error is CancellationException) throw error
    }
}
