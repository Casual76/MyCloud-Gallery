package dev.pampa.mycloudgallery.fixtures;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Il generatore del set di prova per il telefono (docs/fase3-progetto.md §5.3, docs/fase3-campo.md).
 *
 * <pre>
 *   powershell -File tools\fixtures\generate.ps1 [cartella-di-uscita] [-SkipBig]
 * </pre>
 *
 * Scrive in {@code <uscita>/MCG-prova/} i file del catalogo che valgono anche sul telefono, le raffiche e le coppie RAW+JPEG,
 * il JPEG da 200 MP (circa 70 MB, a meno di {@code --skip-big}) e {@code MANIFEST.tsv} con i valori attesi. Non committare
 * l'uscita: i file grandi si generano su richiesta. Con {@code --doc <file>} stampa le tabelle di docs/fase3-campo.md.
 */
public final class FixtureGenerator {

  private FixtureGenerator() {}

  public static void main(String[] args) throws IOException {
    File out = new File("tools/fixtures/out");
    boolean skipBig = false;
    File docFile = null;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--skip-big": skipBig = true; break;
        case "--doc": docFile = new File(args[++i]); break;
        default: out = new File(args[i]);
      }
    }
    if (docFile != null) {
      Files.write(docFile.toPath(), FixtureDocs.markdown().getBytes(StandardCharsets.UTF_8));
      System.out.println("Tabelle scritte in " + docFile);
      return;
    }
    File dir = new File(out, "MCG-prova");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Non riesco a creare " + dir);

    List<String> manifest = new ArrayList<>();
    manifest.add("file\tsezione\tatteso\tcartella-originale");
    int written = 0;
    for (Fixture f : FixtureCatalog.all()) {
      if (!f.onDevice) continue;
      Files.write(new File(dir, f.name).toPath(), f.bytes());
      manifest.add(f.name + "\t" + f.section + "\t" + FixtureDocs.deviceHuman(f) + "\t" + f.folder);
      written++;
    }
    for (FixtureCatalog.GroupSet set : FixtureCatalog.groupSets()) {
      for (Fixture f : set.members) {
        File target = new File(dir, f.name);
        if (target.exists()) continue;
        Files.write(target.toPath(), f.bytes());
        manifest.add(f.name + "\tGruppi\t" + set.title + "\t" + f.folder);
        written++;
      }
    }
    if (!skipBig) {
      ExifWriter.Spec exif = new ExifWriter.Spec();
      exif.make = "samsung";
      exif.model = "SM-S931B";
      exif.dateOriginal = "2024:03:12 10:15:30";
      exif.offsetOriginal = "+01:00";
      File big = new File(dir, "grande-200mp.jpg");
      long start = System.currentTimeMillis();
      ImageFixtures.writeLargeJpeg(big, 16320, 12240, exif);
      manifest.add("grande-200mp.jpg\tGrandi\tFoto, 16320×12240 (200 MP), 12/03/2024 10:15:30 (UTC+1), esatta; nel viewer solo a tasselli, nessun OOM\tDCIM/Camera/");
      System.out.println("grande-200mp.jpg: " + (big.length() / 1_000_000) + " MB in " + (System.currentTimeMillis() - start) / 1000 + " s");
      written++;
    }
    Files.write(new File(dir, "MANIFEST.tsv").toPath(), String.join("\n", manifest).concat("\n").getBytes(StandardCharsets.UTF_8));
    System.out.println(written + " file in " + dir.getAbsolutePath());
  }
}
