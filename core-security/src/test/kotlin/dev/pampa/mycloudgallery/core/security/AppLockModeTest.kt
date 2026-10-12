package dev.pampa.mycloudgallery.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockModeTest {

  @Test
  fun ilBloccoUsaSoloLaCredenzialeDiSistema() {
    assertThat(AppLockMode.entries)
      .containsExactly(AppLockMode.OFF, AppLockMode.SYSTEM_CREDENTIAL)
      .inOrder()
  }
}
