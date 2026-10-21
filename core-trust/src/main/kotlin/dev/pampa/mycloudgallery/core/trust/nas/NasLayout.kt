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

  val REPORTS_DIR: SharePath = ROOT.child("family").child("reports")

  fun registryFile(version: Long): SharePath = REGISTRY_DIR.child(versionName(version))

  fun requestFile(deviceId: String): SharePath = REQUESTS_DIR.child(deviceId)

  /**
   * Le segnalazioni "perso" per [targetId] (tipo 7): una serie numerata, nell'area comune (per l'admin) e, con
   * lo stesso percorso, nell'area privata dell'account (prova di possesso dell'account, revisione B6).
   */
  fun reportDir(targetId: String): SharePath = REPORTS_DIR.child(targetId)

  fun reportFile(targetId: String, version: Long): SharePath = reportDir(targetId).child(versionName(version))

  /** Lo stato pubblicato da un dispositivo (tipo 8), nell'area comune. */
  fun statusDir(deviceId: String): SharePath = ROOT.child("devices").child(deviceId).child("status")

  fun statusFile(deviceId: String, version: Long): SharePath = statusDir(deviceId).child(versionName(version))

  // --- Dati di un dispositivo -------------------------------------------------------------------------
  fun headDir(deviceId: String, kind: String): SharePath = ROOT.child("devices").child(deviceId).child(kind).child("head")

  fun headFile(deviceId: String, kind: String, version: Long): SharePath = headDir(deviceId, kind).child(versionName(version))

  fun segmentFile(deviceId: String, kind: String, hash: String): SharePath =
    ROOT.child("devices").child(deviceId).child(kind).child("seg").child(hash)

  // --- Fase 6: dati AI e copie dei modelli (A1, D6, D18) -------------------------------------------------------------------

  /** Il tipo (e la cartella) delle teste dei dati AI: `devices/<id>/ai/` (D6). */
  const val AI_KIND: String = "ai"

  /** Una testa `ai`: `devices/<id>/ai/head/<versione>` (versione 2 del formato, serie propria). */
  fun aiHead(deviceId: String, version: Long): SharePath = headFile(deviceId, AI_KIND, version)

  /** Un segmento `vec` o `ocr`: `devices/<id>/ai/seg/<impronta>`. */
  fun aiSegment(deviceId: String, hash: String): SharePath = segmentFile(deviceId, AI_KIND, hash)

  /**
   * La copia di un modello pubblicata da un dispositivo nell'area comune (D18): `devices/<id>/models/<sha256>`, file grezzo senza
   * busta ne' estensione (eccezione dichiarata al §2.1). [sha256] in esadecimale minuscolo (64 cifre), come nel catalogo dei modelli.
   */
  fun modelCopy(deviceId: String, sha256: String): SharePath {
    require(SHA256_HEX.matches(sha256)) { "Impronta del modello non valida." }
    return ROOT.child("devices").child(deviceId).child("models").child(sha256)
  }

  private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

  // --- Prenotazioni ---------------------------------------------------------------------------------------
  /** `leases/<impronta della risorsa>/<gruppo>/<generazione>`: gruppi di 256 generazioni (revisione 1.4, M6). */
  fun leaseDir(resource: String): SharePath = ROOT.child("leases").child(resourceName(resource))

  fun leaseShardOf(generation: Long): Long = (generation - 1) / TrustLimits.LEASE_SHARD_SIZE + 1

  fun leaseShardDir(resource: String, shard: Long): SharePath = leaseDir(resource).child(versionName(shard))

  fun leaseFile(resource: String, generation: Long): SharePath =
    leaseShardDir(resource, leaseShardOf(generation)).child(versionName(generation))

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
  /**
   * Un kit per ogni chiave di famiglia (revisione 1.4, M4): dopo una rotazione il kit nuovo ha un posto suo,
   * e quello vecchio si riconosce e si cancella (azione locale confermata, Fase 2.4).
   */
  fun recoveryKitFile(familyId: String, keyFingerprint: String): SharePath =
    RECOVERY_DIR.child(familyId).child(keyFingerprint)

  /** `recovery/`: una cartella per famiglia, un file per chiave. Il recupero dell'admin la elenca (solo nomi validi). */
  val RECOVERY_DIR: SharePath = ROOT.child("recovery")

  // --- Nomi numerati ----------------------------------------------------------------------------------
  private const val VERSION_DIGITS = 20

  /** 20 cifre con zeri a sinistra: l'ordine dei nomi e' l'ordine dei numeri. */
  fun versionName(version: Long): String {
    require(version >= 1) { "Le versioni partono da 1." }
    return version.toString().padStart(VERSION_DIGITS, '0')
  }

  /** Il numero di un nome lungo esattamente 20 cifre ASCII, tra 1 e [TrustLimits.MAX_VERSION], oppure null. */
  fun parseVersionName(name: String): Long? {
    if (name.length != VERSION_DIGITS || !name.all { it in '0'..'9' }) return null
    return name.toLongOrNull()?.takeIf { it in 1..TrustLimits.MAX_VERSION }
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
