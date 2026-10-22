package dev.pampa.mycloudgallery.fixtures.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Il testo INVENTATO del set OCR sintetico (docs/fase6-progetto.md §5.3, D12, D13): negozi, vie, persone e messaggi che non esistono,
 * nessun dato personale. Quattro generi di scena: scontrini, chat, schermate e cartelli, con accenti, apostrofi, euro, cifre e
 * punteggiatura. Deterministico: lo stesso seme da' lo stesso testo (i valori attesi del manifesto sono questi).
 */
final class OcrTexts {

  private OcrTexts() {}

  /** I generi di scena (la categoria del manifesto). */
  enum Genre {
    SCONTRINO("scontrino"),
    CHAT("chat"),
    SCHERMATA("schermata"),
    CARTELLO("cartello");

    final String code;

    Genre(String code) {
      this.code = code;
    }

    static Genre of(String code) {
      for (Genre g : values()) if (g.code.equals(code)) return g;
      throw new IllegalArgumentException("Genere sconosciuto: " + code);
    }
  }

  // Negozi, vie e luoghi inventati: nessuno esiste (ogni nome e' un'invenzione, niente marchi veri).
  private static final String[] SHOPS = {
    "Emporio Belvedere", "Alimentari Da Gigetto", "Forneria Santa Lucilla", "Cartoleria Il Pennino", "Ortofrutta Fratelli Zanchi",
    "Bar Tabacchi Nuvolari", "Macelleria Ferrucci", "Pizzeria Al Cantuccio", "Farmacia Sant'Erasmo", "Enoteca La Botticella",
  };
  private static final String[] STREETS = {
    "Via dei Girasoli 14", "Piazza della Pergola 3", "Corso Vittorio Emanuele 120", "Via dell'Olmo 7", "Viale dei Cipressi 58",
    "Via San Domenico 21", "Largo Cavour 9", "Via della Fornace 33",
  };
  private static final String[] CITIES = {
    "50100 Firenzuola Alta", "40100 Borgo Marina", "10100 Torrechiara", "70100 Valsecca", "20100 Montefalco Nuovo",
  };
  private static final String[][] GOODS = {
    {"Pomodori pelati 400 g", "1,29"}, {"Caffè espresso", "1,10"}, {"Latte intero 1 L", "1,45"}, {"Pane casereccio", "2,30"},
    {"Mozzarella di bufala", "3,99"}, {"Olio extravergine 0,75 L", "7,80"}, {"Yogurt alla frutta", "0,89"}, {"Biscotti all'avena", "2,15"},
    {"Acqua frizzante 6 x 1,5 L", "3,20"}, {"Gelato alla nocciola", "4,50"}, {"Carta da cucina", "2,79"}, {"Tè verde in filtri", "2,40"},
    {"Più sapone liquido", "1,99"}, {"Perché non provare il miele?", "5,60"}, {"Città del vino rosso", "9,90"},
  };
  private static final String[] NAMES = {"Marta", "Gianluca", "Ilaria", "Tommaso", "Beatrice", "Edoardo", "Chiara", "Nicolò"};
  private static final String[] MESSAGES = {
    "Ci vediamo alle 18:30 davanti al bar?", "Ho prenotato per sabato, siamo in 6.", "Dov'è finito il caricabatterie?",
    "Sì, perché no! Porto io il dolce.", "Il treno parte alle 7:45 dal binario 3.", "Quanto costa? Più o meno € 25,00?",
    "È tutto pronto, manca solo il pane.", "L'appuntamento è confermato per giovedì.", "Grazie mille, a più tardi!",
    "Scusa il ritardo, c'è traffico in città.", "Hai visto l'offerta? Sconto del 20% fino a domenica.", "Ok, d'accordo. Ti chiamo stasera.",
  };
  private static final String[] SETTINGS = {
    "Connessioni e rete", "Suoni e vibrazione", "Notifiche non lette", "Schermo e luminosità", "Batteria e prestazioni",
    "Archiviazione e backup", "Privacy e sicurezza", "Posizione e permessi", "Account e sincronizzazione", "Accessibilità e lingua",
  };
  private static final String[] SETTINGS_DETAIL = {
    "Wi-Fi, Bluetooth, modalità aereo", "Volume, suoneria, non disturbare", "Mostra le ultime 5 notifiche", "Tema scuro, ora legale",
    "Risparmio energetico: attivo", "Libera € 0,00 di spazio", "Blocco schermo, impronta digitale", "Consenti solo durante l'uso",
  };
  private static final String[] SIGNS = {
    "Uscita di sicurezza", "Vietato l'ingresso ai non addetti", "Parcheggio € 1,50 all'ora", "Ristorante Da Nonna Pina — aperto tutti i giorni",
    "Caffetteria e pasticceria artigianale", "Farmacia di turno: Sant'Erasmo", "Città di Valsecca, benvenuti!", "Prezzo al chilo € 3,99",
    "Più avanti: 300 m, zona a traffico limitato", "È vietato fumare", "Orario: lunedì-venerdì 8:30-19:00",
  };

  /** Il testo di una scena (le righe, nell'ordine di lettura) di [genre] con il seme [seed]: al piu' [maxLines] righe. */
  static List<String> lines(Genre genre, long seed, int maxLines) {
    Random random = new Random(seed * 7919L + genre.ordinal());
    List<String> out = new ArrayList<>();
    switch (genre) {
      case SCONTRINO:
        out.add(pick(SHOPS, random).toUpperCase(java.util.Locale.ITALIAN));
        out.add(pick(STREETS, random));
        out.add(pick(CITIES, random));
        out.add("Scontrino n. " + String.format("%04d", random.nextInt(9000) + 100) + " del " + date(random));
        double total = 0;
        int items = Math.max(3, maxLines - 7);
        for (int i = 0; i < items; i++) {
          String[] g = GOODS[random.nextInt(GOODS.length)];
          out.add(g[0] + "  € " + g[1]);
          total += Double.parseDouble(g[1].replace(',', '.'));
        }
        out.add("Totale complessivo € " + String.format(java.util.Locale.ITALY, "%.2f", total));
        out.add("IVA 22% inclusa nel prezzo");
        out.add("Grazie per la vostra visita!");
        break;
      case CHAT:
        for (int i = 0; i < Math.max(4, maxLines); i++) {
          out.add(pick(NAMES, random) + ": " + pick(MESSAGES, random));
        }
        break;
      case SCHERMATA:
        out.add("Impostazioni");
        for (int i = 0; i < Math.max(4, maxLines / 2); i++) {
          out.add(SETTINGS[(i + random.nextInt(3)) % SETTINGS.length]);
          out.add(SETTINGS_DETAIL[(i * 3 + random.nextInt(3)) % SETTINGS_DETAIL.length]);
        }
        break;
      case CARTELLO:
        for (int i = 0; i < Math.max(2, Math.min(maxLines, 6)); i++) out.add(pick(SIGNS, random));
        break;
      default:
        throw new IllegalStateException();
    }
    return out.size() > maxLines ? new ArrayList<>(out.subList(0, maxLines)) : out;
  }

  private static String pick(String[] values, Random random) {
    return values[random.nextInt(values.length)];
  }

  private static String date(Random random) {
    return String.format("%02d/%02d/%d", random.nextInt(28) + 1, random.nextInt(12) + 1, 2023 + random.nextInt(3));
  }
}
