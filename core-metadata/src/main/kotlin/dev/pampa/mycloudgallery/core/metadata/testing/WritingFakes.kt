package dev.pampa.mycloudgallery.core.metadata.testing

import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataWriter
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.contract.WriteOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteUnsupported
import java.io.File
import java.util.Locale

// Fase 5B (P0-B): i fake di scrittura dei metadati e di rimozione della posizione, per i test di P8 (correzioni), P11 (condivisione
// senza posizione) e P12 (UI). In `testing` come gli altri: mai nella release (tools/check.ps1). Non producono immagini valide: scrivono
// byte riconoscibili, perche' a chi li usa serve vedere **che cosa** e' stato chiesto, non decodificare un JPEG.

/** Il tipo di un file dal nome (i fake non leggono i byte): le estensioni delle fixture. */
private fun mimeOfName(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
  "jpg", "jpeg" -> "image/jpeg"
  "png" -> "image/png"
  "webp" -> "image/webp"
  "heic", "heif" -> "image/heic"
  "mp4" -> "video/mp4"
  "mov" -> "video/quicktime"
  else -> "application/octet-stream"
}

/**
 * Uno scrittore di metadati finto: i tre formati della Q6 (JPEG, PNG, WebP), nessun altro. Copia il sorgente nella destinazione e vi aggiunge
 * in coda una riga `MCG-FAKE-WRITE <campi>` (i campi toccati, mai i valori): un test legge la coda e sa che cosa e' stato scritto. Non tocca
 * mai il sorgente; una destinazione che esiste e' un rifiuto; un guasto programmato ([failNext]) non lascia niente.
 */
class FakeMetadataWriter(
  private val writable: Set<String> = setOf("image/jpeg", "image/png", "image/webp"),
  private val mimeOf: (File) -> String = ::mimeOfName,
) : MetadataWriter {

  /** I cambiamenti chiesti, nell'ordine. */
  val changes: MutableList<MetadataChange> = ArrayList()

  /** Se valorizzato, la prossima scrittura fallisce con questo motivo (una volta sola) e non crea niente. */
  var failNext: WriteFailure? = null

  override fun canWrite(mime: String): Boolean = mime.lowercase(Locale.ROOT) in writable

  override fun apply(src: File, dst: File, change: MetadataChange): WriteOutcome {
    if (!canWrite(mimeOf(src))) return WriteOutcome.Unsupported(WriteUnsupported.FORMAT)
    if (change.isEmpty) return WriteOutcome.Unsupported(WriteUnsupported.NOTHING_TO_DO)
    if (!src.isFile) return WriteOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    if (dst.exists()) return WriteOutcome.Failed(WriteFailure.DESTINATION_EXISTS)
    failNext?.let {
      failNext = null
      return WriteOutcome.Failed(it)
    }
    changes += change
    val marker = "\nMCG-FAKE-WRITE ${change.fields.joinToString(",") { it.code }}\n".toByteArray(Charsets.US_ASCII)
    val written = src.readBytes() + marker
    dst.writeBytes(written)
    return WriteOutcome.Written(written.size.toLong())
  }

  /** Un test legge cosa e' stato scritto: i campi della coda `MCG-FAKE-WRITE`, o null se il file non e' di questo fake. */
  fun fieldsWritten(file: File): List<String>? {
    val text = file.readBytes().toString(Charsets.US_ASCII)
    val at = text.lastIndexOf("MCG-FAKE-WRITE ")
    return if (at < 0) null else text.substring(at + "MCG-FAKE-WRITE ".length).trim().split(',').filter { it.isNotEmpty() }
  }
}

/**
 * Un togli-posizione finto che si comporta come il contratto: JPEG, PNG, WebP, MP4 e MOV si "ripuliscono" (copia con la riga
 * `MCG-FAKE-STRIPPED` in coda), HEIC diventa JPEG, ogni altro tipo e' [StripUnsupported.FORMAT]. [stillHasLocation] simula il ricontrollo
 * che trova una posizione rimasta: la destinazione non resta e la risposta e' [StripOutcome.StillHasLocation]. Non tocca mai il sorgente.
 */
class FakeLocationStripper(
  private val supported: Set<String> = setOf("image/jpeg", "image/png", "image/webp", "video/mp4", "video/quicktime", "image/heic"),
) : LocationStripper {

  /** Le richieste, per tipo. */
  val requests: MutableList<String> = ArrayList()

  /** Se vero il ricontrollo trova ancora una posizione (D26): la condivisione si ferma. */
  var stillHasLocation: Boolean = false

  /** Se valorizzato, la prossima richiesta fallisce con questo motivo (una volta sola). */
  var failNext: WriteFailure? = null

  override fun strip(src: File, dst: File, mime: String): StripOutcome {
    val type = mime.lowercase(Locale.ROOT)
    requests += type
    if (type !in supported) return StripOutcome.Unsupported(StripUnsupported.FORMAT)
    if (!src.isFile) return StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE)
    if (dst.exists()) return StripOutcome.Failed(WriteFailure.DESTINATION_EXISTS)
    failNext?.let {
      failNext = null
      return StripOutcome.Failed(it)
    }
    if (stillHasLocation) return StripOutcome.StillHasLocation
    dst.writeBytes(src.readBytes() + "\nMCG-FAKE-STRIPPED\n".toByteArray(Charsets.US_ASCII))
    return StripOutcome.Stripped(if (type == "image/heic") "image/jpeg" else type)
  }

  /** Il file e' una destinazione di questo fake. */
  fun isStripped(file: File): Boolean = file.isFile && file.readBytes().toString(Charsets.US_ASCII).contains("MCG-FAKE-STRIPPED")
}

/** Un [GeoChange] per i test: comodo per costruire un [MetadataChange] senza coordinate vere. */
object ChangeSamples {
  val clearLocation: MetadataChange = MetadataChange(geo = GeoChange.Clear)

  val newDate: MetadataChange = MetadataChange(takenAtUtcMillis = 1_760_000_000_000L, offsetMinutes = 120)
}
