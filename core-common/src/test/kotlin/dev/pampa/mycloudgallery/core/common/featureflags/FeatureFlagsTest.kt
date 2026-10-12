package dev.pampa.mycloudgallery.core.common.featureflags

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeatureFlagsTest {

  @Test
  fun inReleaseIlWolNonSiAccendeNemmenoChiedendolo() {
    assertThat(FeatureFlags.forBuild(debugBuild = false, wol = true).wol).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = false, wol = true)).isEqualTo(FeatureFlags.RELEASE)
  }

  @Test
  fun inDebugSiPuoAccendereEDiDefaultESpento() {
    assertThat(FeatureFlags.forBuild(debugBuild = true).wol).isFalse()
    assertThat(FeatureFlags.forBuild(debugBuild = true, wol = true).wol).isTrue()
  }
}
