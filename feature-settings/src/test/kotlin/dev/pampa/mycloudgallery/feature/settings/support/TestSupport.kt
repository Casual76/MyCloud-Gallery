package dev.pampa.mycloudgallery.feature.settings.support

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Il dispatcher principale dei test: i `viewModelScope` girano subito, senza aspettare un Looper. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
  override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

  override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * Tiene acceso uno `StateFlow` costruito con `stateIn(WhileSubscribed)`: senza chi lo osserva il valore resta
 * quello iniziale. Come farebbe la schermata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.keepAlive(flow: StateFlow<*>) {
  backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}
