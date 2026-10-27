package dev.pampa.mycloudgallery.core.faces.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.common.files.Patience
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabases
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.security.faces.FacesKeys
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import java.io.File
import java.util.Random
import kotlin.math.sqrt

// Cio' che le prove del negozio dei volti (P6) condividono: dove stanno i file, come si costruiscono i corpi sintetici (solo numeri casuali,
// nessun volto, nessun nome di persone vere: §5.2) e come si cerca una sequenza di byte in un file.

/**
 * Un negozio vero (Room, Robolectric) in una cartella temporanea, con le chiavi finte di P0 ([fake]); [wrap] le fa passare da un'altra
 * implementazione (un registratore, una chiave che non risponde) senza perdere l'accesso al fake.
 */
internal class StoreWorld(val directory: File, val fake: FakeFacesKeys = FakeFacesKeys(), wrap: (FakeFacesKeys) -> FacesKeys = { it }) {
  val keys: FacesKeys = wrap(fake)
  val context: Context = ApplicationProvider.getApplicationContext()

  /** Senza attese: le prove non dipendono dall'orologio (un file tenuto aperto si prova con la cartella piena, non con un'attesa). */
  val stores = FacesStores(directory, FacesDatabases.forContext(context), keys, patience = Patience.NONE)

  suspend fun ready(generation: FaceGeneration): FacesStore = (stores.open(generation) as FacesStoreOpen.Ready).store

  fun mainFile(generation: FaceGeneration): File = File(directory, "faces-${generation.base32}.db")

  /** Tutti i file del database di [generation] che ci sono: principale, `-wal`, `-shm`, `-journal`. */
  fun filesOf(generation: FaceGeneration): List<File> =
    listOf("", "-wal", "-shm", "-journal").map { File(mainFile(generation).path + it) }.filter { it.exists() }

  /** I byte di tutti i file del database (anche il `-wal` con le scritture non ancora riportate nel file principale). */
  fun bytesOf(generation: FaceGeneration): List<ByteArray> = filesOf(generation).map { it.readBytes() }
}

internal object FaceFixtures {
  const val SEED = 77L

  /** Gli autori sono id di dispositivo qualunque: l'archivio non li interpreta. */
  const val AUTHOR_SMALL = "00000000000000000000000000"
  const val AUTHOR_BIG = "zzzzzzzzzzzzzzzzzzzzzzzzzz"

  fun key(n: Int): ContentKey = GroupingStories.contentKey(SEED, n)

  /** Un vettore normalizzato casuale ma deterministico, in float16: ogni [seed] da' un vettore diverso. */
  fun vector(seed: Int): ShortArray {
    val random = Random(seed * 7_919L + 13)
    val v = FloatArray(FaceSpaceCode.CURRENT.dimension) { random.nextGaussian().toFloat() }
    val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
    return Float16.encode(FloatArray(v.size) { v[it] / norm })
  }

  fun landmarks(seed: Int): ShortArray = ShortArray(10) { (1_000 + seed * 13 + it * 97).toShort() }

  fun entry(index: Int, tier: FaceTier, seed: Int = index, frame: Int = 0): FaceEntry = FaceEntry(
    index = index,
    frame = frame,
    box = FaceBox(index * 700 + 100, 1_000, index * 700 + 600, 6_000),
    landmarks = landmarks(seed),
    score = 200,
    tier = tier,
    reasons = if (tier == FaceTier.CORE) 0 else FaceReasons.SMALL,
    sidePx = 120,
    yaw = 0,
    sharpness = 100,
    vector = if (tier.hasVector) vector(seed) else null,
  )

  fun body(
    key: ContentKey,
    author: String = AUTHOR_SMALL,
    recipe: Int = 1,
    faces: List<FaceEntry> = listOf(entry(0, FaceTier.CORE)),
    digest: Long = recipe * 1_000L + author.hashCode(),
  ) = FaceBody(key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, recipe, FaceSource.ANALYSIS, author, faces, digest)

  /** Dove [needle] compare in [haystack] (-1 = da nessuna parte). */
  fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > haystack.size) return -1
    val first = needle[0]
    var i = 0
    val last = haystack.size - needle.size
    while (i <= last) {
      if (haystack[i] == first) {
        var j = 1
        while (j < needle.size && haystack[i + j] == needle[j]) j++
        if (j == needle.size) return i
      }
      i++
    }
    return -1
  }

  /** I byte di [halves] come stanno nel corpo del formato (big endian) e come li scriverebbe una macchina little endian. */
  fun bigEndian(halves: ShortArray, from: Int = 0, count: Int = 8): ByteArray =
    ByteArray(count * 2) { i -> val h = halves[from + i / 2].toInt(); (if (i % 2 == 0) h shr 8 else h).toByte() }

  fun littleEndian(halves: ShortArray, from: Int = 0, count: Int = 8): ByteArray =
    ByteArray(count * 2) { i -> val h = halves[from + i / 2].toInt(); (if (i % 2 == 0) h else h shr 8).toByte() }

  /** Gli stessi valori come float32 (little e big endian): come li scriverebbe un database che salvasse il vettore decodificato. */
  fun float32(halves: ShortArray, from: Int = 0, count: Int = 4, bigEndian: Boolean): ByteArray {
    val buffer = java.nio.ByteBuffer.allocate(count * 4).order(if (bigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until count) buffer.putFloat(Float16.toFloat(halves[from + i]))
    return buffer.array()
  }
}
