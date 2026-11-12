package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.FencedFlags
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le prenotazioni dei volti sotto la generazione (Fase 8, P3; D16, D21, A5; la radice delle prenotazioni in `Leases.kt`): sotto
 * `faces/<G>/leases/` dell'area privata, con gli stessi cancelli della pubblicazione (recinzione, stato fresco, generazione corrente, area
 * comune, cancello delle scritture).
 */
class NasFacesLeasesTest {

  private fun leases(world: FacesSyncWorld, device: SyncDevice, flags: FeatureFlags = FencedFlags.of()) =
    NasFacesLeases(device.identity, device.switch, device.share, world.clock, device.gate, flags)

  @Test
  fun custodeEUnitaSottoLaGenerazione() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val mine = leases(world, phone)
    val theirs = leases(world, tablet)
    val held = mine.acquire(world.generation, FacesResource.Cluster) as FacesLease.Held
    assertThat(held.untilNasMillis).isEqualTo(world.clock.nasNow!! + TrustLimits.MAX_LEASE_MILLIS)
    assertThat(theirs.acquire(world.generation, FacesResource.Cluster)).isEqualTo(FacesLease.Busy)
    val unit = FacesResource.WorkUnit(AreaKind.COMMON, "2025-07", 3)
    assertThat(theirs.acquire(world.generation, unit)).isInstanceOf(FacesLease.Held::class.java)
    // Rinnovo: una generazione nuova; dopo un'ora senza rinnovo l'altro la prende e il primo l'ha persa.
    val renewed = mine.renew(world.generation, FacesResource.Cluster, held) as FacesLease.Held
    assertThat(renewed.number).isGreaterThan(held.number)
    world.clock.advanceMillis(TrustLimits.MAX_LEASE_MILLIS + 1)
    assertThat(theirs.acquire(world.generation, FacesResource.Cluster)).isInstanceOf(FacesLease.Held::class.java)
    assertThat(mine.renew(world.generation, FacesResource.Cluster, renewed)).isEqualTo(FacesLease.Lost)
    // Tutto sotto faces/<G>/leases/, niente in leases/ della share, niente nell'area comune.
    val paths = phone.share.paths().map { it.value }
    assertThat(paths).isNotEmpty()
    assertThat(paths.all { it.startsWith(NasLayout.facesLeaseRoot(world.generation).value + "/") }).isTrue()
    assertThat(world.family.common.paths().none { it.isWithin(NasLayout.FACES_DIR) }).isTrue()
  }

  @Test
  fun cancelli() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val mine = leases(world, phone)
    // Una generazione che non e' la corrente non si prenota mai (una cancellata non si ripopola).
    assertThat(mine.acquire(FaceGeneration.fromBytes(ByteArray(16) { 1 }), FacesResource.Cluster)).isEqualTo(FacesLease.Failed)
    phone.switch.fresh.value = false
    assertThat(mine.acquire(world.generation, FacesResource.Cluster)).isEqualTo(FacesLease.Failed)
    phone.switch.fresh.value = true
    phone.switch.state.value = FacesState.On(world.generation, HereState.LOCAL_ONLY)
    assertThat(mine.acquire(world.generation, FacesResource.Cluster)).isEqualTo(FacesLease.Blocked(FacesWriteBlock.TEST_SHARES_ONLY))
    phone.switch.state.value = FacesState.On(world.generation, HereState.ACTIVE)
    phone.block = FacesWriteBlock.FROZEN
    assertThat(mine.acquire(world.generation, FacesResource.Cluster)).isEqualTo(FacesLease.Blocked(FacesWriteBlock.FROZEN))
    phone.block = null
    assertThat(phone.share.writes).isEmpty()
    // D34: sulle share vere nessuna prenotazione.
    val real = world.device("Telefono di casa", "mamma", share = "foto-mamma")
    assertThat(leases(world, real).acquire(world.generation, FacesResource.Cluster)).isEqualTo(FacesLease.Blocked(FacesWriteBlock.TEST_SHARES_ONLY))
    assertThat(real.share.writes).isEmpty()
    // L23: mai nell'area comune.
    val common = world.device("Tablet", "test", share = "test-comune")
    assertThat(leases(world, common).acquire(world.generation, FacesResource.Cluster)).isEqualTo(FacesLease.Blocked(FacesWriteBlock.NO_PRIVATE_AREA))
    // Un guasto del NAS e' un esito.
    phone.share.failNext = 1
    assertThat(mine.acquire(world.generation, FacesResource.Cluster)).isEqualTo(FacesLease.Failed)
    assertThat(mine.acquire(world.generation, FacesResource.Cluster)).isInstanceOf(FacesLease.Held::class.java)
  }
}
