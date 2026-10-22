package dev.pampa.mycloudgallery.fixtures.ocr;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Le due misure del set OCR (docs/fase6-progetto.md §5.3): l'**errore per carattere** (distanza di Levenshtein fra il testo letto
 * e quello disegnato, entrambi con gli spazi ridotti, diviso la lunghezza del disegnato: la stessa definizione di
 * {@code OcrFixtures.characterErrorRate} di core-ai) e la **ricerca per parola** (quante parole del disegnato si ritrovano
 * cercandole nel testo letto, con la stessa normalizzazione della ricerca: minuscolo, senza accenti, apostrofi separati, euro).
 * Pure: provate sulla JVM senza lettore.
 */
final class OcrErrorRate {

  private OcrErrorRate() {}

  /** Il numero di caratteri sbagliati e quelli attesi di un confronto: si sommano per avere l'errore di un gruppo (media pesata). */
  static final class Score {
    final int edits;
    final int expectedChars;
    final int wordsFound;
    final int wordsExpected;

    Score(int edits, int expectedChars, int wordsFound, int wordsExpected) {
      this.edits = edits;
      this.expectedChars = expectedChars;
      this.wordsFound = wordsFound;
      this.wordsExpected = wordsExpected;
    }

    static final Score ZERO = new Score(0, 0, 0, 0);

    Score plus(Score other) {
      return new Score(edits + other.edits, expectedChars + other.expectedChars, wordsFound + other.wordsFound, wordsExpected + other.wordsExpected);
    }

    /** Errore per carattere del gruppo, 0..1+ (puo' superare 1 con molto testo inventato); NaN se non c'era testo atteso. */
    double characterErrorRate() {
      return expectedChars == 0 ? Double.NaN : (double) edits / expectedChars;
    }

    /** Frazione di parole ritrovate dalla ricerca, 0..1; NaN se non c'erano parole attese. */
    double wordRecall() {
      return wordsExpected == 0 ? Double.NaN : (double) wordsFound / wordsExpected;
    }
  }

  /** Il confronto di una immagine: [truth] e [read] sono i testi interi (le righe unite da un a capo). */
  static Score score(String truth, String read) {
    String a = collapse(truth);
    String b = collapse(read);
    int edits = a.isEmpty() ? b.length() : levenshtein(a, b);
    List<String> expected = searchWords(truth);
    Set<String> present = new HashSet<>(words(read));
    int found = 0;
    for (String w : expected) if (present.contains(w)) found++;
    return new Score(edits, a.length(), found, expected.size());
  }

  /** Gli spazi ridotti a uno e tolti ai bordi (le righe diventano spazi: la lettura puo' spezzarle diversamente). */
  static String collapse(String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  static int levenshtein(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) previous[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  /** Come {@code SearchText.normalize} di core-search: "€" -> "euro", minuscolo, senza accenti, tutto cio' che non e' a-z0-9 separa. */
  static String normalize(String text) {
    String euro = text.replace("€", " euro ");
    String plain = Normalizer.normalize(euro.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    return plain.replaceAll("[^a-z0-9]+", " ").trim().replaceAll(" +", " ");
  }

  static List<String> words(String text) {
    List<String> out = new ArrayList<>();
    for (String w : normalize(text).split(" ")) if (!w.isEmpty()) out.add(w);
    return out;
  }

  /** Le parole che un utente cercherebbe davvero: da 3 lettere in su (le parole vuote e le sigle di una o due lettere non contano). */
  static List<String> searchWords(String truth) {
    List<String> out = new ArrayList<>();
    for (String w : words(truth)) if (w.length() >= 3) out.add(w);
    return out;
  }
}
