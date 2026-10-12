package dev.pampa.mycloudgallery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.di.AppModule
import org.junit.Test

class AppModuleTest {

  @Test
  fun hiltFornisceIDispatcherVeri() {
    assertThat(AppModule.provideDispatchers()).isSameInstanceAs(DefaultDispatcherProvider)
  }
}
