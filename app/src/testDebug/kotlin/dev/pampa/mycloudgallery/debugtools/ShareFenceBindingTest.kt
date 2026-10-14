package dev.pampa.mycloudgallery.debugtools

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import org.junit.Test

/**
 * D33 (Fase 4A, P11): in debug la sessione del NAS passa dal recinto delle share di prova ([ShareFence]); in release il
 * recinto non esiste (`DeveloperTools` di src/release torna null e tools/check.ps1 cerca `ShareFence` nel dex di release).
 */
class ShareFenceBindingTest {

  @Test
  fun inDebugLaSessioneDelNasHaIlRecintoDelleShareDiProva() {
    assertThat(DeveloperTools.shareGuard()).isSameInstanceAs(ShareFence)
  }
}
