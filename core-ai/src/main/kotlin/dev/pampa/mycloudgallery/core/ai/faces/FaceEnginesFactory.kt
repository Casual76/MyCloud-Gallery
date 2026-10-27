package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.cutout.ModelBytes
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import java.nio.ByteBuffer

// COME SI APRONO I MODELLI DEI VOLTI (docs/fase8-progetto.md D6, D16, §2.2 "Nel telefono"): `ModelFiles.open` verifica dimensione e SHA-256
// del file in `models/<sha256>` **a ogni apertura**, il descrittore verificato si mappa in memoria (`ModelBytes.map`, come MagicTouch) e
// l'interprete legge quei byte e non un file riaperto per nome. Il modello verificato si chiude con la sessione, per ultimo. Un file
// assente o che non passa la verifica e' `MODEL_UNAVAILABLE` (`FACES_MODEL_MISMATCH` per la Diagnostica; E-A11: il gestore lo toglie e lo
// riestrae dall'APK). Questo file non nomina LiteRT: lo fa solo `FaceLiteRtSessions`.

/** I quattro pezzi dei volti legati ai modelli del catalogo, come li riceve il calcolo (P4); li monta :app (P13). */
object FaceEnginesFactory {

  /**
   * YuNet e SFace veri sopra i file verificati di [files], con [FaceAligner] e [FaceQuality]. Nessun modello si apre qui: ogni motore apre il
   * suo alla prima richiesta e lo rilascia con `release()` (a fine tappa, a `onTrimMemory`, allo stop).
   */
  fun create(
    files: ModelFiles,
    threads: Int = FaceEngineLimits.CPU_THREADS,
    detectorSpec: ModelSpec = ModelCatalog.FACE_YUNET,
    embedderSpec: ModelSpec = ModelCatalog.FACE_SFACE,
  ): FaceEngines = FaceEngines(
    YuNetDetector.create(files, detectorSpec, threads),
    SFaceEmbedder.create(files, embedderSpec, threads),
    FaceAligner,
    FaceQuality,
  )
}

internal object FaceModelOpeners {

  /**
   * Apre YuNet: `ModelFiles.open` (verifica), [bytesOf] (la mappatura del descrittore verificato) e [sessionOf] (l'interprete). Gli ultimi due sono i punti
   * dove le prove sulla JVM mettono i loro (senza Android e senza libreria nativa); sul dispositivo valgono sempre quelli veri.
   */
  fun detector(
    files: ModelFiles,
    spec: ModelSpec,
    threads: Int,
    bytesOf: (VerifiedModel) -> ByteBuffer = ModelBytes::map,
    sessionOf: (ByteBuffer, Int) -> DetectorSession = FaceLiteRtSessions::detector,
  ): DetectorOpener = DetectorOpener {
    val model = files.open(spec.id) ?: throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    try {
      OwnedDetector(sessionOf(bytesOf(model), threads), model)
    } catch (t: Throwable) {
      runCatching { model.close() }
      throw t
    }
  }

  fun embedder(
    files: ModelFiles,
    spec: ModelSpec,
    threads: Int,
    bytesOf: (VerifiedModel) -> ByteBuffer = ModelBytes::map,
    sessionOf: (ByteBuffer, Int) -> EmbedderSession = FaceLiteRtSessions::embedder,
  ): EmbedderOpener = EmbedderOpener {
    val model = files.open(spec.id) ?: throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    try {
      OwnedEmbedder(sessionOf(bytesOf(model), threads), model)
    } catch (t: Throwable) {
      runCatching { model.close() }
      throw t
    }
  }
}

/** Una sessione che chiude con se' il modello verificato (per ultimo). */
private class OwnedDetector(private val inner: DetectorSession, private val model: VerifiedModel) : DetectorSession by inner {
  override fun close() {
    runCatching { inner.close() }
    runCatching { model.close() }
  }
}

private class OwnedEmbedder(private val inner: EmbedderSession, private val model: VerifiedModel) : EmbedderSession by inner {
  override fun close() {
    runCatching { inner.close() }
    runCatching { model.close() }
  }
}
