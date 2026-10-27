package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceDetector
import dev.pampa.mycloudgallery.core.ai.contract.FaceEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.testing.FakeFaceEngines
import dev.pampa.mycloudgallery.core.faces.compute.Faces
import dev.pampa.mycloudgallery.core.faces.compute.SimAccount
import dev.pampa.mycloudgallery.core.faces.compute.SimFacesNas
import dev.pampa.mycloudgallery.core.faces.compute.SimMedia
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.util.Random

// I MEDIA DEL BANCO: il catalogo, le anteprime e i motori finti dei volti (la scena sintetica di ogni media: nessuna immagine, nessun volto vero,
// solo numeri) sono quelli del NAS finto di P4 (`SimFacesNas`, `SimDevice`); qui si inventano i media di un giro dal seme e si fanno arrivare altri.

/** Il tempo che passa mentre i motori finti lavorano (l'orologio del banco avanza: le prenotazioni si rinnovano ogni 20 minuti), senza attese vere. */
internal class ChaosEngines(private val fake: FakeFaceEngines, private val clock: ChaosClock, private val detectMillis: Long = 40, private val embedMillis: Long = 5) {
  var detects = 0
    private set

  private val detector = object : FaceDetector {
    override suspend fun detect(image: FaceImage, pass: DetectPass): List<DetectedFace> {
      detects++
      clock.advance(detectMillis)
      return fake.detector.detect(image, pass)
    }

    override suspend fun release() = fake.detector.release()
  }

  private val embedder = object : FaceEmbedder {
    override val space = fake.embedder.space

    override suspend fun embed(faces: List<AlignedFace>): List<FloatArray?> {
      clock.advance(embedMillis * faces.size)
      return fake.embedder.embed(faces)
    }

    override suspend fun release() = fake.embedder.release()
  }

  val engines: FaceEngines get() = FaceEngines(detector, embedder, fake.aligner, fake.quality)
}

/** I media del mondo: un NAS finto con l'area privata `test`, l'area comune e i media del solo telefono dei dispositivi. */
internal class ChaosMedia(private val random: Random, commonShare: String = "test-comune") {
  val nas = SimFacesNas(commonShare)
  val account = SimAccount("test", "test", FaceGeneration.fromBytes(ByteArray(16) { (it + 1).toByte() }))

  private val months = listOf("2024-03", "2024-02", "2023-12", "2023-07")

  /** Un media a sorte: quasi sempre una foto con uno o piu' volti, ogni tanto un documento (escluso), un video, una foto senza volti, un volto piccolo. */
  fun add(share: String): ContentKey {
    // Poche identita': con tre volti della stessa in due foto nasce una persona (D20), e senza persone non ci sono istantanee da pubblicare.
    val id = 1 + random.nextInt(3)
    val month = months[random.nextInt(months.size)]
    return when (random.nextInt(20)) {
      0 -> nas.add(share, listOf(Faces.big(id)), month = month, document = true)
      1 -> nas.add(share, listOf(Faces.big(id)), month = month, kind = MediaKind.VIDEO)
      2 -> nas.add(share, emptyList(), month = month)
      3 -> nas.add(share, listOf(Faces.small(id)), month = month)
      4 -> nas.add(share, listOf(Faces.big(id)), month = month, source = MediaSource.SCREENSHOT)
      5, 6, 7 -> nas.add(share, Faces.group(2 + random.nextInt(3), firstIdentity = 1 + random.nextInt(2)), month = month)
      else -> nas.add(share, listOf(Faces.big(id)), month = month)
    }
  }

  /** Un media solo sul telefono di un dispositivo (arrivera' sul NAS con il backup). */
  fun phoneOnly(): SimMedia = nas.phoneMedia(listOf(Faces.big(1 + random.nextInt(3))), month = months[random.nextInt(months.size)])

  /** Com'e' fatto un media, per spiegare una violazione (mai un contenuto): tipo, origine, se ha l'HQ e se l'anteprima sta sul NAS. */
  fun describe(key: ContentKey): String {
    val m = nas.find(key) ?: return "assente dal NAS (solo telefono?)"
    return "${m.kind.code} ${m.source.name.lowercase()} mese ${m.month} volti ${m.scene.faces.size} hq=${m.hq} anteprima-sul-nas=${m.previewOnNas} doc=${m.document} ritaglio=${m.cutout} nascosto=${m.hidden}"
  }

  /** Il mondo di partenza di un giro. */
  fun populate(privateCount: Int, commonCount: Int) {
    repeat(privateCount) { add(account.privateShare) }
    repeat(commonCount) { add(nas.commonShare) }
  }

  /** Tutti i media del NAS (area privata e comune) che il calcolo deve analizzare: chiavi di contenuto, senza gli esclusi. */
  fun analyzable(areas: Collection<FacesArea>): Set<ContentKey> {
    val out = LinkedHashSet<ContentKey>()
    for (area in areas) {
      val share = if (area == FacesArea.PRIVATE) account.privateShare else nas.commonShare
      for ((key, media) in nas.mediaIn(share)) if (!media.item(area).excluded) out += key
    }
    return out
  }
}
