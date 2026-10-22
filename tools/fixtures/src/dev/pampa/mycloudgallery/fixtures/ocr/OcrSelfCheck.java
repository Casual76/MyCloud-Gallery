package dev.pampa.mycloudgallery.fixtures.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Le prove del generatore OCR sul solo JDK (nessun framework di test: {@code generate-ocr.ps1 -Verifica}): errore per carattere e
 * ricerca per parola su casi noti, il formato delle colonne, il testo inventato (accenti, apostrofi, euro, nessun testo vuoto),
 * il determinismo, le degradazioni e il lettore finto (errore crescente al calare dei pixel). Esce con un errore se una cosa non torna.
 */
public final class OcrSelfCheck {

  private OcrSelfCheck() {}

  private static int failures;

  public static void main(String[] args) throws IOException {
    errorRate();
    columns();
    texts();
    rendering();
    degradations();
    fakeReader();
    if (failures > 0) throw new IllegalStateException(failures + " controlli falliti");
    System.out.println("Tutti i controlli del generatore OCR sono passati.");
  }

  private static void errorRate() {
    check("distanza kitten/sitting = 3", OcrErrorRate.levenshtein("kitten", "sitting") == 3);
    OcrErrorRate.Score same = OcrErrorRate.score("Totale € 24,50", "Totale  €  24,50\n");
    check("testo uguale (spazi diversi) = errore 0", same.edits == 0 && same.characterErrorRate() == 0.0);
    OcrErrorRate.Score one = OcrErrorRate.score("abcd", "abxd");
    check("un carattere su quattro = 25%", Math.abs(one.characterErrorRate() - 0.25) < 1e-9);
    OcrErrorRate.Score none = OcrErrorRate.score("pomodori pelati", "");
    check("niente letto = errore 100%", Math.abs(none.characterErrorRate() - 1.0) < 1e-9 && none.wordRecall() == 0.0);
    check("normalizza accenti, euro e apostrofi", OcrErrorRate.normalize("Più € 3,99 dell'Esselunga").equals("piu euro 3 99 dell esselunga"));
    OcrErrorRate.Score words = OcrErrorRate.score("Caffè espresso € 1,10", "CAFFE espresso euro 1 10 extra");
    check("ricerca per parola ignora accenti e maiuscole", words.wordsExpected == 3 && words.wordsFound == 3);
    OcrErrorRate.Score sum = one.plus(none);
    check("i gruppi sommano pesando per i caratteri", sum.expectedChars == 4 + 15 && sum.edits == 1 + 15);
  }

  private static void columns() {
    String raw = "riga uno\n\triga \\ due";
    check("escape e unescape tornano uguali", OcrSetGenerator.Tsv.unescape(OcrSetGenerator.Tsv.escape(raw)).equals(raw));
    check("l'a capo non sta nella colonna", !OcrSetGenerator.Tsv.escape(raw).contains("\n"));
  }

  private static void texts() {
    for (OcrTexts.Genre genre : OcrTexts.Genre.values()) {
      List<String> a = OcrTexts.lines(genre, 7, 12);
      check(genre.code + ": deterministico", a.equals(OcrTexts.lines(genre, 7, 12)));
      check(genre.code + ": righe non vuote", !a.isEmpty() && a.size() <= 12 && a.stream().noneMatch(String::isBlank));
    }
    String all = String.join("\n", OcrTexts.lines(OcrTexts.Genre.SCONTRINO, 3, 40));
    check("scontrino con euro e totale", all.contains("€") && all.contains("Totale complessivo"));
  }

  private static void rendering() {
    for (OcrTexts.Genre genre : OcrTexts.Genre.values()) {
      for (int px : new int[] {10, 40}) {
        OcrRender.Scene scene = OcrRender.render(genre, 1024, px, 5);
        check(genre.code + " " + px + "px: immagine 1024x1365 con testo", scene.image.getWidth() == 1024 && scene.image.getHeight() == 1365 && !scene.lines.isEmpty());
        check(genre.code + " " + px + "px: ridisegnata uguale", scene.lines.equals(OcrRender.render(genre, 1024, px, 5).lines));
      }
    }
  }

  private static void degradations() throws IOException {
    BufferedImage page = OcrSetGenerator.clean(OcrTexts.Genre.CHAT, 512, 16, 2);
    java.awt.Color bg = new java.awt.Color(0xECE5DD);
    byte[] clean = OcrDegrade.apply(page, OcrDegrade.Kind.PULITA, bg, 1, 0.9f);
    for (OcrDegrade.Kind kind : OcrDegrade.Kind.values()) {
      byte[] one = OcrDegrade.apply(page, kind, bg, 1, 0.9f);
      check(kind.code + ": e' un JPEG", one.length > 3 && (one[0] & 0xFF) == 0xFF && (one[1] & 0xFF) == 0xD8);
      check(kind.code + ": deterministico", java.util.Arrays.equals(one, OcrDegrade.apply(page, kind, bg, 1, 0.9f)));
      if (kind != OcrDegrade.Kind.PULITA) check(kind.code + ": cambia i byte", !java.util.Arrays.equals(one, clean));
    }
    check("JPEG povero pesa meno del pulito", OcrDegrade.apply(page, OcrDegrade.Kind.JPEG, bg, 1, 0.9f).length < clean.length);
    check("il rumore pesa di piu' del pulito", OcrDegrade.apply(page, OcrDegrade.Kind.RUMORE, bg, 1, 0.9f).length > clean.length);
  }

  private static void fakeReader() {
    check("piu' piccolo = piu' errore", OcrFakeReader.errorProbability(10, "pulita") > OcrFakeReader.errorProbability(20, "pulita"));
    check("sfocata peggio di pulita", OcrFakeReader.errorProbability(12, "sfocata") > OcrFakeReader.errorProbability(12, "pulita"));
    OcrReport.Row row = new OcrReport.Row("x-1", "chat", 1024, 12, "pulita", "Ci vediamo alle 18:30 davanti al bar?\nGrazie mille, a piu tardi!");
    check("lettore finto deterministico", OcrFakeReader.read(row).equals(OcrFakeReader.read(row)));
  }

  private static void check(String what, boolean ok) {
    if (!ok) {
      failures++;
      System.err.println("FALLITO: " + what);
    }
  }
}
