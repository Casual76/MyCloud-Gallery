package dev.pampa.mycloudgallery.core.models.verify

import android.system.ErrnoException
import android.system.Os
import dev.pampa.mycloudgallery.core.models.contract.FileStat
import dev.pampa.mycloudgallery.core.models.contract.FileStatReader
import java.io.FileDescriptor

/**
 * Lo `stat` vero (`fstat` del descrittore su cui il verificatore ha calcolato dimensione e SHA-256): dispositivo, inode, dimensione e
 * `ctime` (I1 della revisione di sicurezza della Fase 6). Sta in un file suo perche' `android.system.Os` non gira sulla JVM dei test: solo
 * `:app` lo istanzia, e i test della verifica usano un lettore finto. `fstat` che fallisce = nessuno `stat`: il motore ripiega sul
 * confronto del descrittore, come prima.
 */
class AndroidFileStatReader : FileStatReader {
  override fun stat(descriptor: FileDescriptor): FileStat? =
    try {
      val st = Os.fstat(descriptor)
      FileStat(device = st.st_dev, inode = st.st_ino, size = st.st_size, ctimeSeconds = st.st_ctime,
        ctimeNanos = st.st_ctim.tv_nsec, mtimeSeconds = st.st_mtim.tv_sec, mtimeNanos = st.st_mtim.tv_nsec)
    } catch (_: ErrnoException) {
      null
    }
}
