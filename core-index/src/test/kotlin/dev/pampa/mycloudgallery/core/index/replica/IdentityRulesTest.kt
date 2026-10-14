package dev.pampa.mycloudgallery.core.index.replica

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.replica.store.IdentityOutcome
import dev.pampa.mycloudgallery.core.index.replica.store.SharedIdentity
import dev.pampa.mycloudgallery.core.index.sync.Fx
import dev.pampa.mycloudgallery.core.index.sync.Harness
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** D7 (con i limiti di D8) su scenari scritti a mano, e E-I18 attraverso la sincronizzazione. */
class IdentityRulesTest {

  @get:Rule val tmp = TemporaryFolder()

  private val k1 = Fx.key(1)
  private val k2 = Fx.key(2)
  private fun remote(media: Long, key: dev.pampa.mycloudgallery.core.model.ContentKey = k1) = SharedIdentity(Fx.mediaId(media), Fx.versionId(media), key)

  @Test
  fun stessoIdGiaLegato() {
    assertThat(IdentityRules.decide(remote(5), LocalBinding(Fx.mediaId(5), k1), setOf(k1))).isEqualTo(IdentityOutcome.ALREADY)
  }

  @Test
  fun fileNuovoQuiAdottaGliIdDellIndice() {
    assertThat(IdentityRules.decide(remote(5), local = null, remoteIdHere = null)).isEqualTo(IdentityOutcome.ADOPTED)
  }

  @Test
  fun dueIdPerLoStessoFileConvergonoAlMinore() {
    assertThat(IdentityRules.decide(remote(5), LocalBinding(Fx.mediaId(9), k1), null)).isEqualTo(IdentityOutcome.CONVERGED)
    assertThat(IdentityRules.decide(remote(9), LocalBinding(Fx.mediaId(5), k1), null)).isEqualTo(IdentityOutcome.KEPT_LOCAL)
    // Anche se il legame locale viene solo dall'indice (impronta non letta qui): l'id remoto e' sconosciuto, nessuna unione.
    assertThat(IdentityRules.decide(remote(5), LocalBinding(Fx.mediaId(9), null), null)).isEqualTo(IdentityOutcome.CONVERGED)
  }

  @Test
  fun rifiutoSeLIdRemotoELegatoQuiAUnContenutoDiverso() {
    // Un dispositivo compromesso pubblica per il file X l'id di una foto del telefono con altri byte: rifiutato.
    assertThat(IdentityRules.decide(remote(5, k1), local = null, remoteIdHere = setOf(k2))).isEqualTo(IdentityOutcome.CONFLICT)
    assertThat(IdentityRules.decide(remote(5, k1), LocalBinding(Fx.mediaId(9), k1), setOf(k2))).isEqualTo(IdentityOutcome.CONFLICT)
  }

  @Test
  fun rifiutoSeIlRecordDescriveAltriByteDaQuelliLettiQui() {
    assertThat(IdentityRules.decide(remote(5, k1), LocalBinding(Fx.mediaId(9), k2), null)).isEqualTo(IdentityOutcome.CONFLICT)
  }

  @Test
  fun d8_nessunaUnioneDiUnMediaEsistenteSullaParolaDellIndice() {
    // L'id remoto e' gia' di un media qui (es. del telefono) con lo stesso contenuto, ma questo file del NAS non e' stato
    // letto qui: si aspetta la scansione (impronta letta qui).
    assertThat(IdentityRules.decide(remote(5, k1), local = null, remoteIdHere = setOf(k1))).isEqualTo(IdentityOutcome.DEFERRED)
    assertThat(IdentityRules.decide(remote(5, k1), LocalBinding(Fx.mediaId(9), null), setOf(k1))).isEqualTo(IdentityOutcome.DEFERRED)
    // Con l'impronta letta qui uguale, l'unione e' lecita.
    assertThat(IdentityRules.decide(remote(5, k1), LocalBinding(Fx.mediaId(9), k1), setOf(k1))).isEqualTo(IdentityOutcome.CONVERGED)
  }

  @Test
  fun lIdSeparatoEDeterministicoEDiversoPerPercorsoEContenuto() {
    val a = IdentityRules.separateId("alessio", "foto/a.jpg", k1)
    assertThat(IdentityRules.separateId("alessio", "foto/a.jpg", k1)).isEqualTo(a)
    assertThat(IdentityRules.separateId("alessio", "foto/b.jpg", k1)).isNotEqualTo(a)
    assertThat(IdentityRules.separateId("alessio", "foto/a.jpg", k2)).isNotEqualTo(a)
    assertThat(IdentityRules.separateId("test-comune", "foto/a.jpg", k1)).isNotEqualTo(a)
  }

  @Test
  fun eI18_dueDispositiviConMediaIdDiversiPerLoStessoFileConvergonoAlMinoreSenzaPerderePreferitiEAlbum() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val path = "Viaggi/2024/IMG_7.jpg"
    // Prima pubblica solo "c" con l'id 40.
    nas.publish("alessio", "c", listOf(Fx.meta(Fx.put(path, 1, Fx.file(7, media = 40)))))
    val me = Harness("b", tmp.root, nas)
    me.sync("alessio")
    val pathKey = PathKeys.of(Fx.path(path))
    assertThat(me.catalog.mediaAt("alessio", pathKey)).isEqualTo(Fx.mediaId(40))
    // L'utente lo mette nei preferiti e in un album con l'id che conosce.
    me.catalog.favorite(Fx.mediaId(40))
    me.catalog.addToAlbum("vacanze", Fx.mediaId(40))

    // Poi "a" pubblica lo stesso file (stessi byte, stesso timbro) con l'id 12.
    nas.publish("alessio", "a", listOf(Fx.meta(Fx.put(path, 1, Fx.file(7, media = 12)))))
    val report = me.sync("alessio")
    assertThat(report.identityConflicts).isEqualTo(0)
    assertThat(me.catalog.mediaAt("alessio", pathKey)).isEqualTo(Fx.mediaId(12))
    assertThat(me.catalog.resolve(Fx.mediaId(40))).isEqualTo(Fx.mediaId(12))
    assertThat(me.catalog.isFavorite(Fx.mediaId(12))).isTrue()
    assertThat(me.catalog.albumMembers("vacanze")).containsExactly(Fx.mediaId(12))
    assertThat(me.catalog.outcomes[IdentityOutcome.CONVERGED]).isEqualTo(1)

    // Un terzo dispositivo che arriva dopo vede subito l'id minore, senza passare dal maggiore.
    val late = Harness("d", tmp.root, nas)
    late.sync("alessio")
    assertThat(late.catalog.mediaAt("alessio", pathKey)).isEqualTo(Fx.mediaId(12))
    assertThat(late.catalog.outcomes).containsExactly(IdentityOutcome.ADOPTED, 1)
  }

  @Test
  fun d7_unDispositivoCompromessoNonFondeUnMediaDelTelefonoConUnFileDiverso() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val me = Harness("b", tmp.root, nas)
    // Una foto del telefono con id 3 e il suo contenuto.
    me.catalog.addPhoneMedia(Fx.mediaId(3), Fx.key(3))
    // Il dispositivo "x" pubblica il file 8 del NAS (altri byte) con l'id della foto del telefono.
    nas.publish("alessio", "x", listOf(Fx.meta(Fx.put("Altro/8.jpg", 1, Fx.file(8, media = 3, prepared = setOf(SegmentClass.THUMB_S))))))
    val report = me.sync("alessio")
    assertThat(report.identityConflicts).isEqualTo(1)
    assertThat(me.engine.counters.identityConflicts.get()).isEqualTo(1)
    val separate = me.catalog.mediaAt("alessio", PathKeys.of(Fx.path("Altro/8.jpg")))
    assertThat(separate).isNotEqualTo(Fx.mediaId(3))
    assertThat(separate).isEqualTo(IdentityRules.separateId("alessio", PathKeys.of(Fx.path("Altro/8.jpg")), Fx.key(8)))
    assertThat(me.catalog.resolve(Fx.mediaId(3))).isEqualTo(Fx.mediaId(3))
  }

  @Test
  fun d8_conLaScansioneCheHaLettoLImprontaQuiLUnioneDiventaLecita() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val me = Harness("b", tmp.root, nas)
    me.catalog.addPhoneMedia(Fx.mediaId(3), Fx.key(9))
    nas.publish("alessio", "x", listOf(Fx.meta(Fx.put("Foto/9.jpg", 1, Fx.file(9, media = 3)))))
    me.sync("alessio")
    val key = PathKeys.of(Fx.path("Foto/9.jpg"))
    assertThat(me.catalog.outcomes[IdentityOutcome.DEFERRED]).isEqualTo(1)
    assertThat(me.catalog.mediaAt("alessio", key)).isNotEqualTo(Fx.mediaId(3))

    // La scansione elenca il file e ne legge l'impronta: uguale. Al giro dopo (testa nuova) l'unione converge al minore.
    me.catalog.scan("alessio", key, NasStamp(Fx.content(9).size.toLong(), Fx.NOW), Fx.key(9))
    nas.publish("alessio", "x", listOf(Fx.meta(Fx.put("Foto/9.jpg", 2, Fx.file(9, media = 3)))))
    me.sync("alessio")
    assertThat(me.catalog.mediaAt("alessio", key)).isEqualTo(Fx.mediaId(3))
  }
}
