package dev.pampa.mycloudgallery.core.mediastore.write

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.domain.Observation
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

// "SALVA COME NUOVA FOTO" del ritaglio del soggetto (docs/fase6-progetto.md D47, §2.10, E-V11; Fase 6, P13): un PNG trasparente che
// diventa una foto del telefono in `Pictures/MyCloud Gallery/Ritagli/` e, da li', una sorgente del backup.
//
// La scrittura e' quella di "Scarica sul telefono" (`MediaStorePhoneMediaWriter`, F4-D20): riga con `IS_PENDING`, byte con lo SHA-256
// calcolato mentre arrivano, **collegamento al catalogo con la copia ancora in attesa**, e solo dopo la riga diventa visibile. Qui il
// collegamento e' quello di un media NUOVO (non la copia di uno che c'e' gia'): una osservazione del telefono con la data di adesso,
// e, se il ritaglio viene da un media nascosto, la foto nuova **nasce nascosta** prima che chiunque possa vederla (E-V11, E-H1).

/** Le cartelle del telefono dove scrive l'app (`RELATIVE_PATH` del MediaStore, con la barra finale). */
object PhoneMediaFolders {

  /** "Scarica sul telefono" (D20). */
  const val DOWNLOADS: String = "Pictures/MyCloud Gallery/"

  /** I ritagli del soggetto (D47): una sorgente del backup, accesa di default. */
  const val CUTOUTS: String = "Pictures/MyCloud Gallery/Ritagli/"

  /** La recinzione delle prove (F5-D13): con un account di prova, in debug, l'app scrive solo sotto `mcg-prova`. */
  const val FENCED_ROOT: String = "Pictures/mcg-prova/"

  /** I ritagli in debug con un account di prova: `Pictures/mcg-prova/Ritagli/`. */
  const val FENCED_CUTOUTS: String = "Pictures/mcg-prova/Ritagli/"

  fun cutouts(fenced: Boolean): String = if (fenced) FENCED_CUTOUTS else CUTOUTS
}

/** I nomi dei file dei ritagli. */
object CutoutNames {

  /** Il nome di un ritaglio di [sourceName] (il file di cui e' il soggetto): `IMG_0042-ritaglio.png`; senza nome, `Ritaglio.png`. */
  fun displayName(sourceName: String?): String {
    val file = sourceName?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
    val stem = if (file.lastIndexOf('.') > 0) file.substringBeforeLast('.') else file
    val base = stem.filter { it >= ' ' && it != '\u007f' && it !in FORBIDDEN }.trim().take(MAX_BASE).trim()
    return if (base.isEmpty()) "Ritaglio.png" else "$base-ritaglio.png"
  }

  /** I caratteri che un nome di MediaStore non puo' avere: il separatore dei percorsi e quelli vietati dai file system esterni. */
  private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

  private const val MAX_BASE = 120
}

/**
 * Salva un ritaglio come foto nuova (D47).
 *
 * **Visibile implica collegato.** `beforePublish` del writer riceve la copia ancora in attesa: qui si registra nel catalogo come media
 * nuovo del telefono (stessa posizione che il MediaStore avra' scritto, stessa impronta dei byte) e, dai Nascosti, lo si nasconde. Se
 * una delle due cose non riesce il file non si pubblica ([SaveResult.Failed] con `LINK_FAILED`) e la riga in attesa si toglie. Quando
 * la lettura del telefono vede la riga trova un posto gia' noto con gli stessi byte (`SameFile`): nessun doppione, e mai un istante in
 * cui il ritaglio e' visibile senza il suo "nascosto".
 *
 * La stessa immagine salvata due volte ha gli stessi byte: il catalogo la ritrova come lo stesso media (con un secondo posto). Se il
 * secondo salvataggio viene dai Nascosti, il media diventa nascosto (si sbaglia dal lato della riservatezza, E-H1).
 *
 * **Il "nascosto" nasce con il media** (VR-1, R19): il catalogo registra il posto e il segno nella **stessa transazione** (`record(hidden = true)`), quindi
 * l'indice della Libreria non vede mai la foto senza il suo "nascosto" e c'e' un cambiamento solo da annunciare, non due. Chi chiama (`:app`) avvia poi la
 * sincronizzazione dei dati personali, come per ogni "Nascondi": il segno raggiunge i dispositivi dello stesso account senza aspettare il giro periodico.
 *
 * **Dai Nascosti il nome e' generico** (I6): "Ritaglio.png", non quello del file d'origine. Nel MediaStore il ritaglio e' una foto pubblica (la vedono Galleria
 * di Samsung e le sincronizzazioni delle altre app): un nome come "Passaporto-ritaglio.png" direbbe cos'e' la foto nascosta da cui viene.
 *
 * Mai nei log: il nome del file, il percorso, l'identita' del media (D40).
 */
class CutoutPhoneSaver(
  private val writer: PhoneMediaWriter,
  private val catalog: MediaCatalog,
) {

  sealed interface Outcome {
    /** Salvato: il media nel catalogo e il nome con cui il MediaStore l'ha scritto. */
    data class Saved(val mediaId: MediaId, val displayName: String) : Outcome {
      override fun toString(): String = "Saved"
    }

    /** [code] come quelli di [SaveResult.Failed]: `PHONE_FULL`, `MEDIASTORE_ERROR`, `LINK_FAILED`... */
    data class Failed(val code: String) : Outcome

    data object Cancelled : Outcome
  }

  /**
   * @param png i byte del file (gia' senza metadati, `CutoutRender.png`).
   * @param sourceName il nome del media da cui viene il ritaglio, per dare il nome al file; null = `Ritaglio.png`. Dai Nascosti ([hidden]) non si usa mai (I6).
   * @param hidden il ritaglio viene da un media nascosto: la foto nuova nasce nascosta (E-V11).
   * @param nowMillis la data di adesso (la porta dell'app, mai l'orologio di qui): data del file e di scatto della foto nuova.
   */
  suspend fun save(png: ByteArray, sourceName: String?, hidden: Boolean, nowMillis: Long): Outcome {
    val fingerprint = Fingerprint.ofContent(png)
    var linked: MediaId? = null
    val source = PhoneSaveSource { sink ->
      val buffer = ByteArray(CHUNK)
      var offset = 0
      while (offset < png.size) {
        val n = minOf(CHUNK, png.size - offset)
        System.arraycopy(png, offset, buffer, 0, n)
        sink(buffer, n)
        offset += n
      }
    }
    val result = writer.saveToPictures(
      request = SaveRequest(CutoutNames.displayName(if (hidden) null else sourceName), "image/png", png.size.toLong()),
      source = source,
      expectedSha256 = fingerprint.fullHash,
    ) { copy -> linked = link(copy, fingerprint, hidden, nowMillis) }
    return when (result) {
      is SaveResult.Saved -> linked?.let { Outcome.Saved(it, result.copy.location.displayName) } ?: Outcome.Failed(LINK_FAILED)
      SaveResult.Cancelled -> Outcome.Cancelled
      is SaveResult.Failed -> Outcome.Failed(result.code)
    }
  }

  /** Il media nuovo nel catalogo, nascosto se serve: tutto prima che il file sia visibile. Lancia se non riesce (il file non si pubblica). */
  private suspend fun link(copy: PendingCopy, fingerprint: Fingerprint, hidden: Boolean, nowMillis: Long): MediaId {
    val location = copy.location
    val source = SourceClassifier.classify(location.volume, location.relativePath, location.displayName, null, "image/png").source
    val outcome = try {
      catalog.record(
        Observation(
          location = location,
          stamp = FileStamp(copy.size, nowMillis),
          fingerprint = fingerprint,
          kind = MediaKind.PHOTO,
          source = source,
          captureTimeHintMillis = nowMillis,
        ),
        hidden = hidden,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.d(TAG) { "collegamento del ritaglio non riuscito: ${e.javaClass.simpleName}" }
      throw e
    }
    return outcome.mediaId ?: throw IOException("Il catalogo non ha registrato il ritaglio.")
  }

  private companion object {
    const val TAG = "CutoutSaver"
    const val CHUNK = 64 * 1024
    const val LINK_FAILED = "LINK_FAILED"
  }
}
