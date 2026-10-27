package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.sync.FacesWire
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncWorld
import dev.pampa.mycloudgallery.core.faces.sync.SyncWorldFaces
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il verificatore ha i denti (docs/prova-500.md Parte F, §F5): ogni invariante del §5.5 diventa rosso, e solo quello, su un mondo fatto a mano; un mondo sano resta
 * verde. Le mutazioni (`FacesChaosMutationTest`) provano il banco intero; qui si prova il giudice, senza giri casuali.
 */
class FacesChaosVerifierTest {

  private fun verify(world: ChaosWorld, phase: String = "prova"): ChaosVerifier = ChaosVerifier(world).also { it.check(phase) }

  private fun only(verifier: ChaosVerifier, invariant: Invariant) {
    assertWithMessage("violazioni: ${verifier.found}").that(verifier.found.map { it.invariant }.toSet()).containsExactly(invariant)
  }

  @Test
  fun unMondoSanoEVerde(): Unit = runBlocking {
    val world = ChaosTestKit.world(devices = 3, privateMedia = 6)
    try {
      ChaosTestKit.turns(world, 8)
      val verifier = verify(world)
      val snapshots = ChaosSnapshots(world, verifier)
      snapshots.check("prova")
      snapshots.converged("prova")
      verifier.erasedAtEnd("prova")
      assertWithMessage("violazioni: ${verifier.found}").that(verifier.found).isEmpty()
      assertThat(world.inj.stats.computedMedia).isAtLeast(3)
    } finally {
      world.close()
    }
  }

  @Test
  fun lAreaComuneConUnFileDeiVoltiEIlPostoSbagliato(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val g = world.devices.first().generation()!!
      world.family.common.plant(NasLayout.facesGenerationDir(g).child("x"), ByteArray(4))
      only(verify(world), Invariant.PLACE)
    } finally {
      world.close()
    }
  }

  @Test
  fun unaScritturaFuoriDallaCartellaDelDispositivoEIlPostoSbagliato(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val a = world.devices[0]
      val b = world.devices[1]
      val g = a.generation()!!
      // A scrive nella cartella di B.
      world.logs.attempts += a.id to NasLayout.facesHead(g, b.id, 1)
      only(verify(world), Invariant.PLACE)
    } finally {
      world.close()
    }
  }

  @Test
  fun unaScritturaSenzaLoStatoFrescoEIlPostoSbagliato(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val a = world.devices[0]
      val g = a.generation()!!
      a.switch.stop()
      world.writeAttempt(a, NasLayout.facesHead(g, a.id, 9))
      only(verify(world), Invariant.PLACE)
    } finally {
      world.close()
    }
  }

  @Test
  fun unVoltoSalvatoDueVolteEUnLavoroRifatto(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val a = world.devices[0]
      val g = a.generation()!!
      val body: FaceBody = FacesWire.build(
        FacesSyncWorld.key(1), FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, a.id,
        listOf(SyncWorldFaces.entry(0, FaceTier.CORE, 0)),
      )!!
      world.bodySaved(a, g, body)
      world.bodySaved(a, g, body)
      only(verify(world), Invariant.WORK)
      // Dopo che i dati locali sono stati tolti non e' piu' lavoro rifatto.
      world.wiped(a, g)
      val fresh = ChaosVerifier(world)
      world.bodySaved(a, g, body)
      assertWithMessage("violazioni: ${fresh.found}").that(fresh.found).isEmpty()
    } finally {
      world.close()
    }
  }

  @Test
  fun unaScritturaDopoOffEUnaViolazioneDellaGenerazione(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val a = world.devices[0]
      val g = a.generation()!!
      world.offSeen[a.id to g] = world.attemptsUnder(a.id, g)
      world.logs.attempts += a.id to NasLayout.facesHead(g, a.id, 99)
      only(verify(world), Invariant.GENERATION)
    } finally {
      world.close()
    }
  }

  @Test
  fun unMediaCancellatoOCambiatoEFuoriDaFaces(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val media = world.untouchable.keys.first()
      world.logs.deleted += world.devices[0].id to media
      only(verify(world), Invariant.OUTSIDE)
      val changed = ChaosTestKit.world()
      try {
        changed.nas.plant(changed.untouchable.keys.first(), "altri byte".toByteArray())
        only(verify(changed), Invariant.OUTSIDE)
        val gone = ChaosTestKit.world()
        try {
          gone.nas.removeAsUser(gone.untouchable.keys.first())
          only(verify(gone), Invariant.OUTSIDE)
        } finally {
          gone.close()
        }
      } finally {
        changed.close()
      }
    } finally {
      world.close()
    }
  }

  @Test
  fun dueDispositiviConLaStessaUnitaSonoUnaViolazioneDelleprenotazioni(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val a = world.devices[0]
      val b = world.devices[1]
      val g = a.generation()!!
      val unit = FacesResource.Cluster
      world.leaseEvent(a, g, unit, FacesLease.Held(1, world.clock.nasMs + 3_600_000))
      world.leaseEvent(b, g, unit, FacesLease.Held(2, world.clock.nasMs + 3_600_000))
      only(verify(world), Invariant.LEASES)
      // Se la prima e' scaduta, o se la presa e' contesa, niente.
      val fine = ChaosTestKit.world()
      try {
        val fa = fine.devices[0]
        val fb = fine.devices[1]
        fine.leaseEvent(fa, fa.generation()!!, unit, FacesLease.Held(1, fine.clock.nasMs + 1_000))
        fine.clock.advance(2_000)
        fine.leaseEvent(fb, fa.generation()!!, unit, FacesLease.Held(2, fine.clock.nasMs + 3_600_000))
        fine.leaseEvent(fa, fa.generation()!!, unit, FacesLease.Contended)
        assertThat(verify(fine).found).isEmpty()
      } finally {
        fine.close()
      }
    } finally {
      world.close()
    }
  }

  @Test
  fun unMediaNonAnalizzatoEUnLavoroCheNonArrivaInFondo(): Unit = runBlocking {
    val world = ChaosTestKit.world(devices = 2, privateMedia = 4)
    try {
      // Nessun calcolo: i media in ambito non hanno la loro voce.
      world.devices.forEach { it.local.archive(it.generation()!!, create = true) }
      val verifier = ChaosVerifier(world)
      ChaosSnapshots(world, verifier).converged("prova")
      assertWithMessage("violazioni: ${verifier.found}").that(verifier.found.map { it.invariant }.toSet()).containsExactly(Invariant.CONVERGENCE)
    } finally {
      world.close()
    }
  }

  @Test
  fun unaCancellazioneNonFinitaESenzaMotivoEUnLavoroCheNonArrivaInFondo(): Unit = runBlocking {
    val world = ChaosTestKit.world()
    try {
      val a = world.devices[0]
      // Il diario dice che non e' finita, e la pagina non dice perche': l'invariante 8 della cancellazione.
      a.journal.stored = dev.pampa.mycloudgallery.core.faces.erase.EraseJournal(
        generation = a.generation()!!, choice = dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice.KEEP_PHOTOS, digest = 1, localOnly = false,
      )
      val verifier = ChaosVerifier(world)
      verifier.erasedAtEnd("prova")
      assertWithMessage("violazioni: ${verifier.found}").that(verifier.found.map { it.invariant }.toSet()).containsExactly(Invariant.CONVERGENCE)
    } finally {
      world.close()
    }
  }

  @Test
  fun unGiroNonCadeSuUnPercorsoCheNonEFacesPerErrore() {
    // I percorsi dei volti stanno tutti sotto .mycloudgallery/faces/ (NasLayout): il verificatore usa quelli, non una regola sua.
    assertThat(NasLayout.FACES_DIR.value).isEqualTo(".mycloudgallery/faces")
    assertThat(SharePath.parse("Foto/a.jpg").isWithin(NasLayout.FACES_DIR)).isFalse()
  }
}
