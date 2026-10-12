package dev.pampa.mycloudgallery.core.trust.nas

import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import java.nio.ByteBuffer

/**
 * I percorsi dei dati dell'app in una share (`docs/formato-nas.md` §2). Ogni nome e' costruito da
 * costanti e da id/hash/numeri gia' validati; i nomi letti da un elenco passano dai parse stretti qui
 * sotto e, se non tornano, si ignorano.
 */
object NasLayout {

  val ROOT: SharePath = SharePath.of(TrustFormat.ROOT_DIR)

  // --- Famiglia (solo nell'area comune) -----------------------------------------------------------------
  val REGISTRY_DIR: SharePath = ROOT.child("family").child("registry")
  val REQUESTS_DIR: SharePath = ROOT.child("family").child("requests")

  fun registryFile(version: Long): SharePath = REGISTRY_DIR.child(versionName(version))

  fun requestFile(deviceId: String): SharePath = REQUESTS_DIR.child(deviceId)

  // --- Dati di un dispositivo -------------------------------------------------------------------------
  fun headDir(deviceId: String, kind: String): SharePath = ROOT.child("devices").child(deviceId).child(kind).child("head")

  fun headFile(deviceId: String, kind: String, version: Long): SharePath = headDir(deviceId, kind).child(versionName(version))

  fun segmentFile(deviceId: String, kind: String, hash: String): SharePath =
    ROOT.child("devices").child(deviceId).child(kind).child("seg").child(hash)

  // --- Prenotazioni ---------------------------------------------------------------------------------------
  fun leaseDir(resource: String): SharePath = ROOT.child("leases").child(resourceName(resource))

  fun leaseFile(resource: String, generation: Long): SharePath = leaseDir(resource).child(versionName(generation))

  fun resourceName(resource: String): String = Hashes.sha256Name(resource.toByteArray(Charsets.UTF_8))

  // --- Cestino ----------------------------------------------------------------------------------------------
  fun trashRecordFile(deviceId: String, trashId: String): SharePath =
    ROOT.child("trash").child(deviceId).child("rec").child(trashId)

  /** Il file cestinato, senza estensione: non compare nei servizi multimediali (E-I13). */
  fun trashItemFile(deviceId: String, trashId: String): SharePath =
    ROOT.child("trash").child(deviceId).child("files").child(trashId)

  // --- Album condivisi (Fase 7: qui solo la posizione) ---------------------------------------------------
  fun albumHeadDir(albumId: String, deviceId: String): SharePath =
    ROOT.child("albums").child(albumId).child(deviceId).child("head")

  // --- Kit di recupero (area privata dell'admin) ------------------------------------------------------
  fun recoveryKitFile(familyId: String): SharePath = ROOT.child("recovery").child(familyId)

  // --- Nomi numerati ----------------------------------------------------------------------------------
  private const val VERSION_DIGITS = 20

  /** 20 cifre con zeri a sinistra: l'ordine dei nomi e' l'ordine dei numeri. */
  fun versionName(version: Long): String {
    require(version >= 1) { "Le versioni partono da 1." }
    return version.toString().padStart(VERSION_DIGITS, '0')
  }

  /** Il numero di un nome lungo esattamente 20 cifre ASCII, oppure null. */
  fun parseVersionName(name: String): Long? {
    if (name.length != VERSION_DIGITS || !name.all { it in '0'..'9' }) return null
    return name.toLongOrNull()?.takeIf { it >= 1 }
  }
}

/**
 * Nomi dei file LOCALI (cache, miniature, replica) derivati da un hash, mai da testo letto dal NAS
 * (`docs/formato-nas.md` §8.3): 53 caratteri `[0-9a-z]`, senza estensione, uguali su ogni dispositivo.
 */
object LocalNames {
  fun of(vararg parts: String): String {
    var size = 0
    val encoded = parts.map { it.toByteArray(Charsets.UTF_8).also { b -> size += 4 + b.size } }
    val buffer = ByteBuffer.allocate(size)
    encoded.forEach { buffer.putInt(it.size).put(it) }
    return "m" + Base32.encode(Hashes.sha256("mycloudgallery/local-name\u0000".toByteArray(), buffer.array()))
  }
}

/** Il tetto di pixel per decodificare un'immagine arrivata dall'indice (piano 1.4). */
object ImageBounds {
  private const val MAX_SIDE = 16384

  fun isDecodable(width: Int, height: Int): Boolean =
    width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= TrustLimits.MAX_INDEX_IMAGE_PIXELS
}
