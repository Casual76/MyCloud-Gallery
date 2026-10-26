package dev.pampa.mycloudgallery.core.ai.noop

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceAlignment
import dev.pampa.mycloudgallery.core.ai.contract.FaceDetector
import dev.pampa.mycloudgallery.core.ai.contract.FaceEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.contract.FaceQualityRule
import dev.pampa.mycloudgallery.core.ai.contract.QualityVerdict
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier

// I null-object dei motori dei volti (Fase 8, P0): falliscono CHIUSI. Nessun modello = nessun volto **e nessun "analizzato, nessun
// volto"**: il rilevatore e l'embedder lanciano `MODEL_UNAVAILABLE`, cosi' il calcolo (P4) si ferma con `FACES_ENGINE_FAILED` invece di
// scrivere per ogni media un corpo vuoto che direbbe il falso (e non si rifarebbe piu'). L'allineamento non si raggiunge mai.

object NoFaceDetector : FaceDetector {
  override suspend fun detect(image: FaceImage, pass: DetectPass): List<DetectedFace> = throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)

  override suspend fun release() = Unit
}

object NoFaceEmbedder : FaceEmbedder {
  override val space: FaceSpaceCode = FaceSpaceCode.CURRENT

  override suspend fun embed(faces: List<AlignedFace>): List<FloatArray?> = throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)

  override suspend fun release() = Unit
}

object NoFaceAlignment : FaceAlignment {
  override fun align(image: FaceImage, landmarks: FloatArray): AlignedFace = throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
}

/** Senza motori nessun volto ha un vettore: NONE. */
object NoFaceQuality : FaceQualityRule {
  override fun assess(face: DetectedFace, sidePx: Int, aligned: AlignedFace?): QualityVerdict = QualityVerdict(FaceTier.NONE, 0, 0, 0)
}

/** I motori dei volti di un dispositivo dove Persone non e' attiva (o i modelli non ci sono): niente gira. */
object NoFaceEnginesFactory {
  val ENGINES: FaceEngines = FaceEngines(NoFaceDetector, NoFaceEmbedder, NoFaceAlignment, NoFaceQuality)
}
