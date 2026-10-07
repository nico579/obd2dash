package com.nico.obd2dash

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Shared by the UI and foreground recording service; never recreates a lost process capture. */
internal class ObdSessionOwner(private val application: Application) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recordingObserver: Job? = null
    private val lifetime = SessionLifetime(
        create = { createModel() },
        destroy = {
            recordingObserver?.cancel()
            recordingObserver = null
            viewModelStore.clear()
        },
        recording = { model: ObdViewModel -> model.state.value.isRecording }
    )

    val current: ObdViewModel? get() = lifetime.current

    fun acquireUi(owner: Any): ObdViewModel = lifetime.acquireUi(owner)
    fun releaseUi(owner: Any, changingConfiguration: Boolean) =
        lifetime.releaseUi(owner, changingConfiguration)

    private fun createModel(): ObdViewModel {
        val model = ViewModelProvider(
            this, ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[ObdViewModel::class.java]
        // Every callback checks the current identity before releasing a session.
        recordingObserver = scope.launch {
            model.state.map { it.isRecording }.distinctUntilChanged().collect {
                lifetime.recordingChanged(model)
            }
        }
        return model
    }
}
