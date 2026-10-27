package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.testing.SyntheticFace
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticScene
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.io.IOException
import java.util.Locale

// IL NAS FINTO del calcolo dei volti (P4, §5.1 "NAS finto"): le share con i loro media (e la scena sintetica di ognuno: nessuna immagine, nessun
// volto vero), le prenotazioni sotto la generazione con l'ora del NAS, i corpi `faces` pubblicati da ogni dispositivo per generazione e i
// guasti. Fedele ai contratti di P0 (prenotazioni di 1 ora, scritture solo nella propria cartella della propria generazione) senza firme ne'
// file: quelli li prova P3. Una scrittura con la generazione sbagliata e' un'asserzione che cade (mai l'area privata di un altro account).

/** Il piano dei guasti (`FaultPlan`): un `IOException` alla chiamata numero [at] (contando tutte le chiamate al NAS), prima o dopo l'effetto. */
class Faults {
  var calls: Int = 0
    private set
  var at: Int = -1
  var after: Boolean = false
  var tripped: Boolean = false
    private set

  fun <T> call(effect: () -> T): T {
    val n = ++calls
    if (n == at && !after) {
      tripped = true
      throw IOException("guasto simulato prima")
    }
    val value = effect()
    if (n == at && after) {
      tripped = true
      throw IOException("guasto simulato dopo")
    }
    return value
  }
}

/** Un account della famiglia finta: la sua area privata e la **generazione** dei suoi dati dei volti. */
class SimAccount(val name: String, val privateShare: String, val generation: FaceGeneration)

/** Un media sul NAS finto o sul telefono: il contenuto e la scena che i motori finti "vedono" (le facce sono numeri). */
class SimMedia(
  val key: ContentKey,
  val kind: MediaKind,
  val month: String,
  val source: MediaSource,
  val scene: SyntheticScene,
  val hq: Boolean,
  val document: Boolean,
  val cutout: Boolean,
  val hidden: Boolean,
  /** L'anteprima attestata nell'indice della share (vero di serie); falso = solo nel buffer di chi l'ha preparata, o da nessuna parte. */
  val previewOnNas: Boolean,
) {
  fun item(area: FacesArea): FacesItem = FacesItem(
    contentKey = key, media = MediaId(java.util.UUID.nameUUIDFromBytes(key.hex.toByteArray()).toString()), kind = kind, source = source,
    document = document, cutout = cutout, month = month, area = area,
  )
}

class SimFacesNas(val commonShare: String = "test-comune") {
  val accounts = LinkedHashMap<String, SimAccount>()
  private val shares = LinkedHashMap<String, LinkedHashMap<ContentKey, SimMedia>>()

  /** Le prenotazioni: (generazione, risorsa) → (dispositivo, scadenza nell'ora del NAS). */
  val leases = HashMap<Pair<FaceGeneration, FacesResource>, Pair<String, Long>>()

  /** L'ultimo numero di prenotazione dato a ogni risorsa (cresce a ogni presa, di chiunque). */
  val leaseNumbers = HashMap<Pair<FaceGeneration, FacesResource>, Long>()

  /** I corpi pubblicati: generazione → dispositivo → chiave → corpo. */
  val published = HashMap<FaceGeneration, HashMap<String, HashMap<ContentKey, FaceBody>>>()

  /** Ogni scrittura sul NAS: (dispositivo, generazione). Il banco controlla che sia sempre la generazione del dispositivo. */
  val writes = ArrayList<Pair<String, FaceGeneration>>()

  var now: Long = 1_800_000_000_000L
  var offline: Boolean = false
  val faults = Faults()
  var previewBytesRead: Long = 0L

  val identities = SyntheticIdentities(seed = 8)
  private var sceneIds = 0
  private var keyCounter = 0

  fun account(name: String): SimAccount = accounts.getOrPut(name) {
    SimAccount(name, "test-privata-$name", FaceGeneration.fromBytes(ByteArray(16) { (name.hashCode() + it).toByte() }))
  }

  fun <T> call(effect: () -> T): T {
    if (offline) throw IOException("NAS spento")
    return faults.call(effect)
  }

  fun key(share: String): String = share.lowercase(Locale.ROOT)

  fun mediaIn(share: String): Map<ContentKey, SimMedia> = shares[key(share)].orEmpty()

  fun find(key: ContentKey): SimMedia? = shares.values.firstNotNullOfOrNull { it[key] }

  fun newKey(): ContentKey = ContentKey((++keyCounter).toString(16).padStart(64, '0'))

  /** Una scena nuova con i volti dati (frazioni dell'immagine). */
  fun makeScene(faces: List<SyntheticFace>, width: Int = 1024, height: Int = 768): SyntheticScene = SyntheticScene(++sceneIds, width, height, faces)

  /** Un media nuovo nella share (nell'area privata di un account o nella comune). */
  fun add(
    share: String,
    faces: List<SyntheticFace> = listOf(Faces.big(1)),
    month: String = "2024-03",
    kind: MediaKind = MediaKind.PHOTO,
    source: MediaSource = MediaSource.CAMERA,
    hq: Boolean = true,
    document: Boolean = false,
    cutout: Boolean = false,
    hidden: Boolean = false,
    previewOnNas: Boolean = true,
    key: ContentKey = newKey(),
    scene: SyntheticScene? = null,
  ): ContentKey {
    val media = SimMedia(key, kind, month, source, scene ?: makeScene(faces), hq, document, cutout, hidden, previewOnNas)
    shares.getOrPut(key(share)) { LinkedHashMap() }[key] = media
    return key
  }

  /** Un media che esiste solo sul telefono (non sul NAS): la scena si prende da qui. */
  fun phoneMedia(
    faces: List<SyntheticFace> = listOf(Faces.big(1)),
    month: String = "2024-03",
    kind: MediaKind = MediaKind.PHOTO,
  ): SimMedia = SimMedia(newKey(), kind, month, MediaSource.CAMERA, makeScene(faces), true, false, false, false, previewOnNas = false)

  /** Il media arriva sul NAS (backup riuscito): lascia il telefono. */
  fun moveToNas(media: SimMedia, share: String) {
    shares.getOrPut(key(share)) { LinkedHashMap() }[media.key] = media
  }

  fun publishedBy(generation: FaceGeneration, device: String): Map<ContentKey, FaceBody> = published[generation]?.get(device).orEmpty()

  fun allPublishedKeys(generation: FaceGeneration): Set<ContentKey> = published[generation].orEmpty().values.flatMapTo(HashSet()) { it.keys }
}

/** L'orologio finto di un dispositivo: il monotono avanza solo quando il test (o i motori) lo dicono, l'ora del NAS e' quella del NAS finto. */
class SimClock(private val nas: SimFacesNas) : FacesClock {
  var monotonic: Long = 1_000_000_000L

  override fun monotonicNanos(): Long = monotonic

  override fun nasNowMillis(): Long? = nas.now

  fun advanceMillis(millis: Long) {
    monotonic += millis * 1_000_000
    nas.now += millis
  }
}

/** I volti sintetici pronti all'uso: grandi (CORE), piccoli (sotto 40 px a 1024, CORE a 2048), minuscoli, di profilo. */
object Faces {
  /** Un volto grande: 25% del lato, CORE gia' dall'anteprima. */
  fun big(identity: Int, x: Float = 0.1f, frame: Int = 0): SyntheticFace =
    SyntheticFace(identity, x, 0.2f, x + 0.25f, 0.2f + 0.25f * 1024f / 768f, frame = frame)

  /** Un volto di ~31 px a 1024 e ~62 px a 2048: voluto-seconda-passata, CORE solo con l'HQ. */
  fun small(identity: Int, x: Float = 0.1f, frame: Int = 0): SyntheticFace =
    SyntheticFace(identity, x, 0.3f, x + 0.03f, 0.3f + 0.03f * 1024f / 768f, frame = frame)

  /** Un volto di ~15 px a 1024 e ~30 px a 2048: WEAK anche con l'HQ. */
  fun tiny(identity: Int, x: Float = 0.1f, frame: Int = 0): SyntheticFace =
    SyntheticFace(identity, x, 0.3f, x + 0.015f, 0.3f + 0.015f * 1024f / 768f, frame = frame)

  /** Un volto grande ma di profilo (oltre 35 gradi): WEAK. */
  fun profile(identity: Int, x: Float = 0.1f, yaw: Int = 50): SyntheticFace =
    SyntheticFace(identity, x, 0.2f, x + 0.25f, 0.2f + 0.25f * 1024f / 768f, yaw = yaw)

  /** [n] volti affiancati (fino a 8), tutti CORE dall'anteprima. */
  fun group(n: Int, firstIdentity: Int = 1): List<SyntheticFace> {
    val step = 0.95f / n
    val width = step * 0.8f
    return (0 until n).map { i ->
      val x0 = 0.02f + i * step
      SyntheticFace(firstIdentity + i, x0, 0.2f, x0 + width, 0.2f + width * 1024f / 768f)
    }
  }
}
