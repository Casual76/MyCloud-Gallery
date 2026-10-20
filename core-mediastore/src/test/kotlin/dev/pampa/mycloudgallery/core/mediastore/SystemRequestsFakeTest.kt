package dev.pampa.mycloudgallery.core.mediastore

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteCodes
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore.DialogAnswer
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore.SystemRequest
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B (P0-B, `docs/fase5-progetto.md` §5.2 "Dialoghi di sistema"): il telefono finto con `createTrashRequest`, `createDeleteRequest` e
 * `createWriteRequest` simulati (l'utente accetta, rifiuta, accetta solo una parte, l'app e' uccisa durante il dialogo), i null-object
 * delle richieste e il riscrittore finto. Sono gli strumenti con cui P8, P9 e P12 provano i loro flussi senza dispositivo.
 */
class SystemRequestsFakeTest {

  @get:Rule val temp = TemporaryFolder()

  private val store = FakeMediaStore().apply { addVolume(VOLUME) }

  private fun put(id: Long, size: Long = 5_000, content: ByteArray? = null): MediaRef.Phone {
    store.put(
      MediaStoreRow(VOLUME, id, RowType.IMAGE, "IMG_$id.jpg", "DCIM/mcg-prova/", "image/jpeg", size = content?.size?.toLong() ?: size, dateModifiedMs = 1_000, dateTakenMs = 2_000),
      content,
    )
    return MediaRef.Phone(VOLUME, id, "image/jpeg")
  }

  @Test
  fun leRichiesteRegistranoSoloCioCheAndroidAccetta() {
    val intents = FakePhoneMediaIntents()
    val refs = listOf(MediaRef.Phone(VOLUME, 1, "image/jpeg"), MediaRef.Phone(VOLUME, 1, "image/jpeg"), MediaRef.Phone(VOLUME, 2, "video/mp4"))
    assertThat(intents.deleteRequest(emptyList())).isNull()
    intents.deleteRequest(refs)
    intents.writeRequest(refs.take(1))
    // Distinte: la stessa riga due volte conta una volta.
    assertThat(intents.deleteRequests.single().map { it.mediaStoreId }).containsExactly(1L, 2L)
    assertThat(intents.writeRequests.single().map { it.mediaStoreId }).containsExactly(1L)
    // Oltre il limite di sistema nessuna richiesta, e nemmeno una parziale.
    val tooMany = (1L..(PhoneMediaIntents.MAX_REQUEST_URIS + 1L)).map { MediaRef.Phone(VOLUME, it, "image/jpeg") }
    intents.deleteRequest(tooMany)
    intents.writeRequest(tooMany)
    assertThat(intents.deleteRequests).hasSize(1)
    assertThat(intents.writeRequests).hasSize(1)
    // Senza il cestino di sistema (Android 10) non esiste nessuna delle tre richieste.
    val old = FakePhoneMediaIntents(canTrash = false)
    old.deleteRequest(refs)
    old.writeRequest(refs)
    assertThat(old.deleteRequests).isEmpty()
    assertThat(old.writeRequests).isEmpty()
    assertThat(NoPhoneMediaIntents.deleteRequest(refs)).isNull()
    assertThat(NoPhoneMediaIntents.writeRequest(refs)).isNull()
  }

  @Test
  fun ilDialogoDiCestinoEliminazioneEScritturaHaGliEffettiDiAndroid() {
    val a = put(1)
    val b = put(2)
    val c = put(3)
    // Cestino: la riga resta ma non si vede; il ripristino la rimette.
    val trash = store.resolveRequest(SystemRequest.TRASH, listOf(a, b), DialogAnswer.Accept)
    assertThat(trash.granted).containsExactly(a, b)
    assertThat(store.isTrashed(VOLUME, 1)).isTrue()
    assertThat(store.isTrashed(VOLUME, 3)).isFalse()
    store.resolveRequest(SystemRequest.UNTRASH, listOf(a), DialogAnswer.Accept)
    assertThat(store.isTrashed(VOLUME, 1)).isFalse()
    // Eliminazione: la riga sparisce davvero.
    store.resolveRequest(SystemRequest.DELETE, listOf(c), DialogAnswer.Accept)
    assertThat(store.row(VOLUME, 3)).isNull()
    // Scrittura: il permesso e' per quelle righe soltanto.
    store.resolveRequest(SystemRequest.WRITE, listOf(a), DialogAnswer.Accept)
    assertThat(store.hasWriteGrant(VOLUME, 1)).isTrue()
    assertThat(store.hasWriteGrant(VOLUME, 2)).isFalse()
    store.clearWriteGrants()
    assertThat(store.hasWriteGrant(VOLUME, 1)).isFalse()
    assertThat(store.resolvedRequests.map { it.first }).containsExactly(
      SystemRequest.TRASH, SystemRequest.UNTRASH, SystemRequest.DELETE, SystemRequest.WRITE,
    ).inOrder()
  }

  @Test
  fun lUtenteRifiutaAccettaUnaParteOLAppMuore() {
    val refs = (1L..4L).map { put(it) }
    // Rifiuta: non cambia niente.
    val refused = store.resolveRequest(SystemRequest.DELETE, refs, DialogAnswer.Refuse)
    assertThat(refused.granted).isEmpty()
    assertThat(refused.delivered).isTrue()
    assertThat(store.row(VOLUME, 1)).isNotNull()
    // Accetta solo una parte: le altre restano.
    val part = store.resolveRequest(SystemRequest.TRASH, refs, DialogAnswer.AcceptOnly(setOf(2L, 4L)))
    assertThat(part.granted.map { it.mediaStoreId }).containsExactly(2L, 4L)
    assertThat(store.isTrashed(VOLUME, 1)).isFalse()
    assertThat(store.isTrashed(VOLUME, 2)).isTrue()
    // L'app muore con il dialogo aperto: l'utente accetta lo stesso, ma l'app non riceve niente.
    val killed = store.resolveRequest(SystemRequest.TRASH, listOf(refs[0]), DialogAnswer.AppKilled(userAccepts = true))
    assertThat(killed.delivered).isFalse()
    assertThat(store.isTrashed(VOLUME, 1)).isTrue()
    val killedRefused = store.resolveRequest(SystemRequest.TRASH, listOf(refs[2]), DialogAnswer.AppKilled(userAccepts = false))
    assertThat(killedRefused.delivered).isFalse()
    assertThat(store.isTrashed(VOLUME, 3)).isFalse()
    // Una riga che non c'e' piu' non ha effetto, come nel dialogo vero.
    assertThat(store.resolveRequest(SystemRequest.DELETE, listOf(MediaRef.Phone(VOLUME, 777, "image/jpeg")), DialogAnswer.Accept).granted).isEmpty()
  }

  @Test
  fun unaRichiestaOltreIlLimiteDiSistemaEUnErroreDiProgrammazione() {
    val tooMany = (1L..(PhoneMediaIntents.MAX_REQUEST_URIS + 1L)).map { MediaRef.Phone(VOLUME, it, "image/jpeg") }
    assertThrows(IllegalArgumentException::class.java) { store.resolveRequest(SystemRequest.TRASH, tooMany, DialogAnswer.Accept) }
  }

  @Test
  fun ilRiscrittoreFintoSiComportaComeIlContratto() = runTest {
    val oldBytes = FakeMediaContent.jpeg(4_000, seed = 1)
    val ref = put(10, content = oldBytes)
    val newBytes = FakeMediaContent.jpeg(3_000, seed = 2)
    val file = temp.newFile().apply { writeBytes(newBytes) }
    val rewriter = FakePhoneMediaRewriter(store)

    // Senza il permesso del dialogo niente si scrive.
    assertThat(rewriter.replace(ref, file, Sha256.of(newBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.NotGranted)
    assertThat(store.bytesOf(VOLUME, 10)!!.toList()).isEqualTo(oldBytes.toList())

    store.resolveRequest(SystemRequest.WRITE, listOf(ref), DialogAnswer.Accept)
    // Un file che non ha lo SHA-256 atteso non si scrive.
    assertThat(rewriter.replace(ref, file, Sha256.of(oldBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.SourceMismatch)
    // Una riga sparita e' Gone.
    assertThat(rewriter.replace(MediaRef.Phone(VOLUME, 404, "image/jpeg"), file, Sha256.of(newBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.Gone)

    val before = store.row(VOLUME, 10)!!
    val result = rewriter.replace(ref, file, Sha256.of(newBytes), Sha256.of(oldBytes)) as RewriteResult.Replaced
    assertThat(result.size).isEqualTo(3_000L)
    assertThat(store.bytesOf(VOLUME, 10)!!.toList()).isEqualTo(newBytes.toList())
    // Stessa riga, stessa identita': solo timbro e generazione nuovi (la lettura del telefono la vede come una modifica).
    val after = store.row(VOLUME, 10)!!
    assertThat(after.stamp()).isNotEqualTo(before.stamp())
    assertThat(after.generationModified!!).isGreaterThan(before.generationModified!!)
    assertThat(rewriter.replaced).containsExactly(VOLUME to 10L)
  }

  @Test
  fun iGuastiDelRiscrittoreFintoRestituisconoLaRigaComeEraOANiente() = runTest {
    val oldBytes = FakeMediaContent.jpeg(4_000, seed = 5)
    val ref = put(11, content = oldBytes)
    val newBytes = FakeMediaContent.jpeg(3_500, seed = 6)
    val file: File = temp.newFile().apply { writeBytes(newBytes) }
    val rewriter = FakePhoneMediaRewriter(store)
    store.resolveRequest(SystemRequest.WRITE, listOf(ref), DialogAnswer.Accept)

    rewriter.corruptNext = true
    assertThat(rewriter.replace(ref, file, Sha256.of(newBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.Failed(RewriteCodes.HASH_MISMATCH, restored = true))
    assertThat(store.bytesOf(VOLUME, 11)!!.toList()).isEqualTo(oldBytes.toList())

    rewriter.failNext = RewriteResult.Failed(RewriteCodes.PHONE_FULL, restored = true)
    assertThat(rewriter.replace(ref, file, Sha256.of(newBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.Failed(RewriteCodes.PHONE_FULL, restored = true))
    assertThat(store.bytesOf(VOLUME, 11)!!.toList()).isEqualTo(oldBytes.toList())

    // Il caso grave: a meta' e non ripristinato. Il telefono ha un file corto: il chiamante lo deve dire.
    rewriter.failNext = RewriteResult.Failed(RewriteCodes.WRITE_ERROR, restored = false)
    assertThat(rewriter.replace(ref, file, Sha256.of(newBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.Failed(RewriteCodes.WRITE_ERROR, restored = false))
    assertThat(store.bytesOf(VOLUME, 11)!!.size).isLessThan(newBytes.size)
    assertThat(rewriter.replaced).isEmpty()
  }

  @Test
  fun senzaIlLegameVeroNienteSiRiscrive() = runTest {
    val ref = put(12)
    val file = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
    assertThat(NoPhoneMediaRewriter.replace(ref, file, Sha256.of(byteArrayOf(1, 2, 3)), Sha256.of(byteArrayOf(4)))).isEqualTo(RewriteResult.NotGranted)
  }

  /**
   * R3 della revisione 5B: il riscrittore finto, come quello vero, scrive solo su una riga che ha l'impronta attesa (la versione al sicuro sul
   * NAS). Una modifica fatta sul telefono e non ancora salvata non si sovrascrive; una riga che ha gia' i byte nuovi e' fatta.
   */
  @Test
  fun r3_unaRigaCheNonHaLImprontaAttesaNonSiTocca() = runTest {
    val oldBytes = FakeMediaContent.jpeg(4_000, seed = 21)
    val unsaved = FakeMediaContent.jpeg(4_200, seed = 22)
    val ref = put(13, content = unsaved)
    val newBytes = FakeMediaContent.jpeg(3_000, seed = 23)
    val file = temp.newFile().apply { writeBytes(newBytes) }
    val rewriter = FakePhoneMediaRewriter(store)
    store.resolveRequest(SystemRequest.WRITE, listOf(ref), DialogAnswer.Accept)

    // La riga ha una modifica che non e' quella al sicuro (oldBytes): niente si scrive.
    assertThat(rewriter.replace(ref, file, Sha256.of(newBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.RowChanged)
    assertThat(store.bytesOf(VOLUME, 13)!!.toList()).isEqualTo(unsaved.toList())
    assertThat(rewriter.replaced).isEmpty()

    // Con i byte nuovi gia' sulla riga (una riscrittura finita con la risposta persa) e' fatta, senza scrivere di nuovo.
    val again = put(14, content = newBytes)
    store.resolveRequest(SystemRequest.WRITE, listOf(again), DialogAnswer.Accept)
    assertThat(rewriter.replace(again, file, Sha256.of(newBytes), Sha256.of(oldBytes))).isEqualTo(RewriteResult.Replaced(Sha256.of(newBytes), 3_000))
    assertThat(rewriter.replaced).isEmpty()
  }

  /**
   * VE-2 della verifica funzionale 5B: il permesso del dialogo vale per l'URI chiesto. Il telefono finto ora lo sa: la collezione e' quella del
   * tipo della riga (come `AndroidPhoneMediaIntents.requestUriOf`), un riferimento senza tipo su una riga che non c'e' e' un `files/<id>` che il
   * sistema rifiuta, e il riscrittore apre la stessa collezione.
   */
  @Test
  fun ve2_ilPermessoValePerLUriDellaCollezioneDelTipoDellaRiga() = runTest {
    store.put(MediaStoreRow(VOLUME, 30, RowType.VIDEO, "VID_30.mp4", "DCIM/mcg-prova/", null, size = 9, dateModifiedMs = 1_000, dateTakenMs = 2_000), ByteArray(9) { 1 })
    // Un riferimento senza MIME (come quello dei dialoghi della 5B): la collezione e' quella del video.
    val untyped = MediaRef.Phone(VOLUME, 30, null)
    assertThat(store.requestCollectionOf(untyped)).isEqualTo(FakeMediaStore.RequestCollection.VIDEO)
    store.resolveRequest(SystemRequest.WRITE, listOf(untyped), DialogAnswer.Accept)
    assertThat(store.hasWriteGrant(VOLUME, 30)).isTrue()
    // Il MIME del riferimento non vince sul tipo della riga.
    val image = put(31)
    store.resolveRequest(SystemRequest.WRITE, listOf(MediaRef.Phone(VOLUME, image.mediaStoreId, "video/mp4")), DialogAnswer.Accept)
    assertThat(store.hasWriteGrant(VOLUME, 31)).isTrue()
    // Una riga che non c'e' e un riferimento senza tipo: `files/<id>`, rifiutato come sul telefono vero.
    assertThrows(IllegalArgumentException::class.java) {
      store.resolveRequest(SystemRequest.WRITE, listOf(MediaRef.Phone(VOLUME, 999, null)), DialogAnswer.Accept)
    }
  }

  private companion object {
    const val VOLUME = "external_primary"
  }
}
