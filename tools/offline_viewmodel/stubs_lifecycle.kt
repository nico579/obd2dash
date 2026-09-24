package androidx.lifecycle

import android.app.Application
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

// A single UI thread preserves the ordering used by Android's ViewModel scope.
// It does not reproduce Android lifecycle callbacks or process death.
val auditDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "audit-ui").apply { isDaemon = true }
}.asCoroutineDispatcher()

open class AndroidViewModel(private val application: Application) {
    val auditScope = CoroutineScope(SupervisorJob() + auditDispatcher)
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = application as T
    protected open fun onCleared() {}
}

val AndroidViewModel.viewModelScope: CoroutineScope get() = auditScope
