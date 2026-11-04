// Fahrtenbuch-Display: Firmware für das ESP32-2432S028R ("Cheap Yellow Display").
//
// Das Display ist ein kleines Info-Tablet am Raspberry Pi. Die display-bridge auf dem Pi
// (server/display-bridge auf Branch "server") schickt JSON-Zeilen, die Firmware zeichnet
// daraus vier Seiten (Übersicht, Fahrten, Rekorde, Mehr) und schickt Anfragen und Befehle
// aus dem Server-Menü zurück. Protokoll: docs/RASPBERRY-PI.md, Abschnitt 2.5.
//
// Anschluss: USB-Kabel (Serial, Variante B) oder GPIO an CN1 (Serial2, RX=GPIO27,
// TX=GPIO22, Variante A). Die Firmware hört und sendet auf beiden gleichzeitig.

#include <Arduino.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <SPI.h>
#include <TFT_eSPI.h>
#include <Update.h>
#include <XPT2046_Touchscreen.h>
#include <lvgl.h>

#include <vector>

#ifndef FW_VARIANTE
#define FW_VARIANTE "cyd"
#endif

// Build-Nummer = Nummer des Releases display-N (setzt die GitHub Action), lokal 0
#ifndef FW_BUILD
#define FW_BUILD 0
#endif

static const int FW_PROTOKOLL = 1;

// ---------------------------------------------------------------------------
// Hardware
// ---------------------------------------------------------------------------

static const int BREITE = 320;
static const int HOEHE = 240;

// Touch (XPT2046) hängt an einem eigenen SPI-Bus
static const int TOUCH_IRQ = 36;
static const int TOUCH_MOSI = 32;
static const int TOUCH_MISO = 39;
static const int TOUCH_CLK = 25;
static const int TOUCH_CS = 33;

// Rohwerte des Touch-Controllers für die Ecken (bei Bedarf anpassen)
static const int TOUCH_X_MIN = 200, TOUCH_X_MAX = 3700;
static const int TOUCH_Y_MIN = 240, TOUCH_Y_MAX = 3800;

static const int UART_RX = 27;  // CN1
static const int UART_TX = 22;  // CN1

static const int BL_PIN = 21;
static const int BL_KANAL = 0;

static TFT_eSPI tft;
static SPIClass touchSpi(VSPI);
static XPT2046_Touchscreen touch(TOUCH_CS, TOUCH_IRQ);

static uint8_t zeichenpuffer[BREITE * 24 * 2];

// Bilddrehung: 0 = quer mit USB-Anschluss rechts, 180 = auf dem Kopf (USB links).
// Wird im NVS gespeichert und gilt ab dem nächsten Start sofort, auch ohne Bridge.
static Preferences einstellungen;
static int drehung = 0;
static bool lvglBereit = false;

static uint8_t tftRotation(int grad) { return grad == 180 ? 3 : 1; }

// ---------------------------------------------------------------------------
// Zustand
// ---------------------------------------------------------------------------

static uint32_t letzteNachricht = 0;   // millis() der letzten Nachricht von der Bridge
static bool datenDa = false;
static uint32_t letztesHallo = 0;

// Uhrzeit aus der Bridge, läuft mit millis() weiter
static long uhrSekundeDesTages = -1;
static uint32_t uhrMillis = 0;
static int uhrWochentag = 0;  // 0 = Montag

static String lesepuffer[2];

static std::vector<String> autoIds;
static std::vector<String> fahrtDetails;
static String appUrl = "";
static String rekordZeitraum = "monat";

// UI-Objekte
static lv_obj_t *kopfTitel, *kopfUhr, *kopfStatus;
static lv_obj_t *tabview;
static lv_obj_t *seiteUebersicht, *seiteFahrten, *seiteRekorde, *seiteMehr;
static lv_obj_t *autoListe, *laeuftLabel;
static lv_obj_t *fahrtenListe;
static lv_obj_t *rekordLabel;
static lv_obj_t *rekordKnoepfe[3];
static lv_obj_t *kostenLabel, *serverLabel, *qr, *urlLabel;
static lv_obj_t *hauptScreen, *menueScreen = nullptr, *detailScreen = nullptr;
static lv_obj_t *pinFeld = nullptr, *tastatur = nullptr;
static lv_obj_t *detailTitel, *detailChart, *detailText;
static lv_chart_series_t *detailSerie;
static lv_obj_t *ausOverlay = nullptr;

// ---------------------------------------------------------------------------
// Hilfen
// ---------------------------------------------------------------------------

static String tausender(long wert) {
  bool negativ = wert < 0;
  String s = String(negativ ? -wert : wert);
  String aus;
  int n = s.length();
  for (int i = 0; i < n; i++) {
    aus += s[i];
    int rest = n - i - 1;
    if (rest > 0 && rest % 3 == 0) aus += '.';
  }
  return negativ ? "-" + aus : aus;
}

static String kommazahl(float wert, int stellen) {
  String s = String(wert, stellen);
  s.replace('.', ',');
  return s;
}

static String euro(float wert) { return kommazahl(wert, 2) + " €"; }

// Ganze Zahl aus einem JSON-Wert, egal ob die Bridge 90 oder 89.9 schickt.
// (Vorsicht: "wert | 0" liefert in ArduinoJson bei Kommazahlen 0, weil der Typ geprüft wird.)
static long ganz(JsonVariantConst v) { return v.is<float>() ? lroundf(v.as<float>()) : 0; }

static lv_color_t farbe(const char *hex) {
  if (!hex || hex[0] != '#') return lv_color_hex(0x888888);
  return lv_color_hex((uint32_t)strtoul(hex + 1, nullptr, 16));
}

static void senden(JsonDocument &doc) {
  String zeile;
  serializeJson(doc, zeile);
  zeile += '\n';
  Serial.print(zeile);
  Serial2.print(zeile);
}

static void sendeHallo() {
  JsonDocument doc;
  doc["t"] = "hallo";
  doc["fw"] = FW_PROTOKOLL;
  doc["variante"] = FW_VARIANTE;
  doc["build"] = FW_BUILD;
  doc["drehung"] = drehung;
  senden(doc);
  letztesHallo = millis();
}

static void hinweis(const char *titel, const String &text) {
  lv_obj_t *mb = lv_msgbox_create(NULL);
  lv_msgbox_add_title(mb, titel);
  lv_msgbox_add_text(mb, text.c_str());
  lv_msgbox_add_close_button(mb);
  lv_obj_set_width(mb, 260);
}

static void hintergrund(int helligkeit) {
  static int aktuell = -1;
  if (helligkeit == aktuell) return;
  aktuell = helligkeit;
  ledcWrite(BL_KANAL, helligkeit);
}

// ---------------------------------------------------------------------------
// LVGL-Anbindung
// ---------------------------------------------------------------------------

static void flush(lv_display_t *disp, const lv_area_t *area, uint8_t *px) {
  uint32_t w = lv_area_get_width(area);
  uint32_t h = lv_area_get_height(area);
  tft.startWrite();
  tft.setAddrWindow(area->x1, area->y1, w, h);
  tft.pushColors((uint16_t *)px, w * h, true);
  tft.endWrite();
  lv_display_flush_ready(disp);
}

static void touchLesen(lv_indev_t *indev, lv_indev_data_t *data) {
  if (touch.tirqTouched() && touch.touched()) {
    TS_Point p = touch.getPoint();
    int x = constrain(map(p.x, TOUCH_X_MIN, TOUCH_X_MAX, 0, BREITE - 1), 0, BREITE - 1);
    int y = constrain(map(p.y, TOUCH_Y_MIN, TOUCH_Y_MAX, 0, HOEHE - 1), 0, HOEHE - 1);
    // Bei 180° spiegeln statt den Touch-Controller umzustellen: so bleibt die Kalibrierung gleich.
    if (drehung == 180) {
      x = BREITE - 1 - x;
      y = HOEHE - 1 - y;
    }
    data->point.x = x;
    data->point.y = y;
    data->state = LV_INDEV_STATE_PRESSED;
  } else {
    data->state = LV_INDEV_STATE_RELEASED;
  }
}

static uint32_t tick() { return millis(); }

static void drehungSetzen(int grad, bool speichern) {
  grad = (grad == 180) ? 180 : 0;
  if (speichern && grad != drehung) {
    einstellungen.begin("fahrtenbuch", false);
    einstellungen.putUShort("drehung", grad);
    einstellungen.end();
  }
  drehung = grad;
  tft.setRotation(tftRotation(drehung));
  if (lvglBereit) {
    lv_obj_invalidate(lv_screen_active());
    lv_obj_invalidate(lv_layer_top());
  }
}

// ---------------------------------------------------------------------------
// Server-Menü
// ---------------------------------------------------------------------------

struct HalteKnopf {
  const char *befehl;
  const char *text;
  bool nachfragen;
  uint32_t start;
  bool ausgeloest;
  lv_obj_t *label;
  lv_obj_t *balken;
};

static HalteKnopf halteKnoepfe[] = {
    {"pb_neustart", "PocketBase neu starten", false, 0, false, nullptr, nullptr},
    {"backup", "Backup jetzt", false, 0, false, nullptr, nullptr},
    {"reboot", "Pi neu starten", true, 0, false, nullptr, nullptr},
    {"poweroff", "Pi ausschalten", true, 0, false, nullptr, nullptr},
};

static void befehlSenden(const char *was) {
  JsonDocument doc;
  doc["t"] = "befehl";
  doc["was"] = was;
  doc["pin"] = pinFeld ? lv_textarea_get_text(pinFeld) : "";
  senden(doc);
}

static void nachfrageJa(lv_event_t *e) {
  const char *was = (const char *)lv_event_get_user_data(e);
  lv_obj_t *btn = (lv_obj_t *)lv_event_get_target(e);
  lv_obj_t *mb = lv_obj_get_parent(lv_obj_get_parent(btn));
  befehlSenden(was);
  lv_msgbox_close(mb);
}

static void nachfrageNein(lv_event_t *e) {
  lv_obj_t *btn = (lv_obj_t *)lv_event_get_target(e);
  lv_msgbox_close(lv_obj_get_parent(lv_obj_get_parent(btn)));
}

static lv_obj_t *halteHinweis = nullptr;

static void halteHinweisSetzen(bool zuKurz) {
  if (!halteHinweis) return;
  lv_label_set_text(halteHinweis, zuKurz ? "Länger halten!" : "Knöpfe 3 s halten");
  lv_obj_set_style_text_color(halteHinweis, zuKurz ? lv_palette_main(LV_PALETTE_AMBER) : lv_color_hex(0x9AA5B1), 0);
}

static void halteEreignis(lv_event_t *e) {
  HalteKnopf *k = (HalteKnopf *)lv_event_get_user_data(e);
  lv_event_code_t code = lv_event_get_code(e);
  if (code == LV_EVENT_PRESSED) {
    k->start = millis();
    k->ausgeloest = false;
    lv_bar_set_value(k->balken, 0, LV_ANIM_OFF);
    halteHinweisSetzen(false);
  } else if (code == LV_EVENT_PRESSING && !k->ausgeloest) {
    uint32_t dauer = millis() - k->start;
    lv_bar_set_value(k->balken, min<uint32_t>(dauer / 30, 100), LV_ANIM_OFF);
    if (dauer >= 3000) {
      k->ausgeloest = true;
      if (k->nachfragen) {
        lv_obj_t *mb = lv_msgbox_create(NULL);
        lv_msgbox_add_title(mb, "Sicher?");
        String frage = String(k->text) + "?";
        if (strcmp(k->befehl, "poweroff") == 0) frage += "\nDanach synchronisiert kein Handy mehr, bis der Stecker kurz gezogen wurde.";
        lv_msgbox_add_text(mb, frage.c_str());
        lv_obj_t *ja = lv_msgbox_add_footer_button(mb, "Ja");
        lv_obj_add_event_cb(ja, nachfrageJa, LV_EVENT_CLICKED, (void *)k->befehl);
        lv_obj_t *nein = lv_msgbox_add_footer_button(mb, "Nein");
        lv_obj_add_event_cb(nein, nachfrageNein, LV_EVENT_CLICKED, nullptr);
        lv_obj_set_width(mb, 280);
      } else {
        befehlSenden(k->befehl);
      }
    }
  } else if (code == LV_EVENT_RELEASED || code == LV_EVENT_PRESS_LOST) {
    if (!k->ausgeloest && millis() - k->start < 3000) halteHinweisSetzen(true);
    lv_bar_set_value(k->balken, 0, LV_ANIM_OFF);
  }
}

static void zurueck(lv_event_t *e) { lv_screen_load(hauptScreen); }

static void zwischenspeicherLeeren();

// Mit PIN, sonst lehnt die Bridge den Befehl ab, sobald auf dem Pi eine PIN gesetzt ist.
// Zwischenspeicher leeren, damit wirklich alles neu gezeichnet wird.
static void neuLaden(lv_event_t *e) {
  zwischenspeicherLeeren();
  befehlSenden("neu_laden");
}

static lv_obj_t *drehLabel = nullptr;

static void drehLabelAktualisieren() {
  if (drehLabel) {
    lv_label_set_text(drehLabel, drehung == 180 ? LV_SYMBOL_REFRESH " Drehen (180°)" : LV_SYMBOL_REFRESH " Drehen (0°)");
  }
}

static lv_obj_t *menueZeile(lv_obj_t *parent) {
  lv_obj_t *z = lv_obj_create(parent);
  lv_obj_set_size(z, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(z, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(z, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_all(z, 0, 0);
  lv_obj_set_style_pad_column(z, 6, 0);
  lv_obj_set_style_border_width(z, 0, 0);
  lv_obj_set_style_bg_opa(z, LV_OPA_TRANSP, 0);
  lv_obj_remove_flag(z, LV_OBJ_FLAG_SCROLLABLE);
  return z;
}

static void drehenGetippt(lv_event_t *e) {
  drehungSetzen(drehung == 180 ? 0 : 180, true);
  drehLabelAktualisieren();
}

static void pinFokus(lv_event_t *e) {
  lv_event_code_t code = lv_event_get_code(e);
  if (code == LV_EVENT_FOCUSED || code == LV_EVENT_CLICKED) {
    lv_keyboard_set_textarea(tastatur, pinFeld);
    lv_obj_remove_flag(tastatur, LV_OBJ_FLAG_HIDDEN);
  } else if (code == LV_EVENT_DEFOCUSED || code == LV_EVENT_READY || code == LV_EVENT_CANCEL) {
    lv_obj_add_flag(tastatur, LV_OBJ_FLAG_HIDDEN);
  }
}

static void menueBauen() {
  menueScreen = lv_obj_create(NULL);
  lv_obj_set_flex_flow(menueScreen, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_all(menueScreen, 6, 0);
  lv_obj_set_style_pad_row(menueScreen, 4, 0);

  // Oben: Zurück, Hinweis, PIN
  lv_obj_t *zeile = menueZeile(menueScreen);
  lv_obj_t *z = lv_button_create(zeile);
  lv_obj_set_height(z, 32);
  lv_obj_add_event_cb(z, zurueck, LV_EVENT_CLICKED, nullptr);
  lv_label_set_text(lv_label_create(z), LV_SYMBOL_LEFT " Zurück");
  halteHinweis = lv_label_create(zeile);
  lv_obj_set_style_text_font(halteHinweis, &fb_font_12, 0);
  halteHinweisSetzen(false);
  pinFeld = lv_textarea_create(zeile);
  lv_textarea_set_one_line(pinFeld, true);
  lv_textarea_set_password_mode(pinFeld, true);
  lv_textarea_set_accepted_chars(pinFeld, "0123456789");
  lv_textarea_set_max_length(pinFeld, 4);
  lv_textarea_set_placeholder_text(pinFeld, "PIN");
  lv_obj_set_width(pinFeld, 80);
  lv_obj_set_style_pad_ver(pinFeld, 6, 0);
  lv_obj_add_event_cb(pinFeld, pinFokus, LV_EVENT_ALL, nullptr);

  // Halte-Knöpfe. Balken bewusst hell auf dunklem Grund, damit er auf blauen und roten Knöpfen sichtbar ist.
  for (auto &k : halteKnoepfe) {
    lv_obj_t *b = lv_button_create(menueScreen);
    lv_obj_set_size(b, LV_PCT(100), 32);
    lv_obj_set_style_pad_ver(b, 0, 0);
    if (strcmp(k.befehl, "poweroff") == 0 || strcmp(k.befehl, "reboot") == 0) {
      lv_obj_set_style_bg_color(b, lv_palette_main(LV_PALETTE_RED), 0);
    }
    k.label = lv_label_create(b);
    lv_label_set_text(k.label, k.text);
    lv_obj_set_width(k.label, LV_PCT(72));
    lv_label_set_long_mode(k.label, LV_LABEL_LONG_DOT);
    lv_obj_align(k.label, LV_ALIGN_LEFT_MID, 0, 0);
    k.balken = lv_bar_create(b);
    lv_obj_set_size(k.balken, 60, 10);
    lv_obj_align(k.balken, LV_ALIGN_RIGHT_MID, 0, 0);
    lv_bar_set_range(k.balken, 0, 100);
    lv_obj_set_style_bg_color(k.balken, lv_color_black(), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(k.balken, LV_OPA_50, LV_PART_MAIN);
    lv_obj_set_style_border_color(k.balken, lv_color_white(), LV_PART_MAIN);
    lv_obj_set_style_border_width(k.balken, 1, LV_PART_MAIN);
    lv_obj_set_style_bg_color(k.balken, lv_color_white(), LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(k.balken, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_remove_flag(k.balken, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(b, halteEreignis, LV_EVENT_ALL, &k);
  }

  // Unten nebeneinander: Neu laden und Drehen (beides harmlos, Tippen reicht)
  lv_obj_t *unten = menueZeile(menueScreen);
  lv_obj_t *n = lv_button_create(unten);
  lv_obj_set_height(n, 32);
  lv_obj_set_flex_grow(n, 1);
  lv_obj_set_style_pad_ver(n, 0, 0);
  lv_obj_add_event_cb(n, neuLaden, LV_EVENT_CLICKED, nullptr);
  lv_obj_t *nl = lv_label_create(n);
  lv_label_set_text(nl, LV_SYMBOL_DOWNLOAD " Neu laden");
  lv_obj_center(nl);
  lv_obj_t *d = lv_button_create(unten);
  lv_obj_set_height(d, 32);
  lv_obj_set_flex_grow(d, 1);
  lv_obj_set_style_pad_ver(d, 0, 0);
  lv_obj_add_event_cb(d, drehenGetippt, LV_EVENT_CLICKED, nullptr);
  drehLabel = lv_label_create(d);
  lv_obj_center(drehLabel);
  drehLabelAktualisieren();

  tastatur = lv_keyboard_create(menueScreen);
  lv_keyboard_set_mode(tastatur, LV_KEYBOARD_MODE_NUMBER);
  lv_obj_add_flag(tastatur, LV_OBJ_FLAG_HIDDEN);
  lv_obj_add_flag(tastatur, LV_OBJ_FLAG_FLOATING);
  lv_obj_set_size(tastatur, BREITE, 120);
  lv_obj_align(tastatur, LV_ALIGN_BOTTOM_MID, 0, 0);
  lv_obj_add_event_cb(tastatur, pinFokus, LV_EVENT_READY, nullptr);
  lv_obj_add_event_cb(tastatur, pinFokus, LV_EVENT_CANCEL, nullptr);
}

static void menueOeffnen(lv_event_t *e) {
  if (!menueScreen) menueBauen();
  for (auto &k : halteKnoepfe) lv_bar_set_value(k.balken, 0, LV_ANIM_OFF);
  halteHinweisSetzen(false);
  lv_screen_load(menueScreen);
}

// ---------------------------------------------------------------------------
// Detailseite pro Auto
// ---------------------------------------------------------------------------

static void detailBauen() {
  detailScreen = lv_obj_create(NULL);
  lv_obj_set_flex_flow(detailScreen, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_all(detailScreen, 6, 0);
  lv_obj_set_style_pad_row(detailScreen, 4, 0);

  lv_obj_t *zeile = lv_obj_create(detailScreen);
  lv_obj_set_size(zeile, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(zeile, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(zeile, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_all(zeile, 0, 0);
  lv_obj_set_style_border_width(zeile, 0, 0);
  lv_obj_set_style_bg_opa(zeile, LV_OPA_TRANSP, 0);
  lv_obj_t *z = lv_button_create(zeile);
  lv_obj_add_event_cb(z, zurueck, LV_EVENT_CLICKED, nullptr);
  lv_label_set_text(lv_label_create(z), LV_SYMBOL_LEFT);
  detailTitel = lv_label_create(zeile);
  lv_obj_set_style_text_font(detailTitel, &fb_font_20, 0);
  lv_label_set_text(detailTitel, "...");

  detailChart = lv_chart_create(detailScreen);
  lv_obj_set_size(detailChart, LV_PCT(100), 90);
  lv_chart_set_type(detailChart, LV_CHART_TYPE_BAR);
  lv_chart_set_point_count(detailChart, 12);
  lv_chart_set_div_line_count(detailChart, 3, 0);
  detailSerie = lv_chart_add_series(detailChart, lv_palette_main(LV_PALETTE_BLUE), LV_CHART_AXIS_PRIMARY_Y);

  detailText = lv_label_create(detailScreen);
  lv_obj_set_width(detailText, LV_PCT(100));
  lv_label_set_long_mode(detailText, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_font(detailText, &fb_font_12, 0);
  lv_label_set_text(detailText, "Lade ...");
}

static void autoAngetippt(lv_event_t *e) {
  size_t i = (size_t)lv_event_get_user_data(e);
  if (i >= autoIds.size()) return;
  if (!detailScreen) detailBauen();
  lv_label_set_text(detailText, "Lade ...");
  lv_chart_set_all_value(detailChart, detailSerie, 0);
  lv_screen_load(detailScreen);
  JsonDocument doc;
  doc["t"] = "details";
  doc["auto"] = autoIds[i];
  senden(doc);
}

// ---------------------------------------------------------------------------
// Hauptseiten
// ---------------------------------------------------------------------------

static lv_obj_t *spalte(lv_obj_t *parent) {
  lv_obj_set_flex_flow(parent, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_all(parent, 4, 0);
  lv_obj_set_style_pad_row(parent, 4, 0);
  return parent;
}

static void rekordWahl(lv_event_t *e) {
  const char *z = (const char *)lv_event_get_user_data(e);
  rekordZeitraum = z;
  for (int i = 0; i < 3; i++) lv_obj_remove_state(rekordKnoepfe[i], LV_STATE_CHECKED);
  lv_obj_add_state((lv_obj_t *)lv_event_get_target(e), LV_STATE_CHECKED);
  JsonDocument doc;
  doc["t"] = "rekorde";
  doc["zeitraum"] = z;
  senden(doc);
}

static void fahrtAngetippt(lv_event_t *e) {
  size_t i = (size_t)lv_event_get_user_data(e);
  if (i < fahrtDetails.size()) hinweis("Fahrt", fahrtDetails[i]);
}

static void farbtest() {
  // Beim Start: drei Farbfelder, damit man sieht, ob die richtige Variante drauf ist.
  lv_obj_t *s = lv_screen_active();
  const char *namen[] = {"Rot", "Grün", "Blau"};
  uint32_t farben[] = {0xFF0000, 0x00FF00, 0x0000FF};
  for (int i = 0; i < 3; i++) {
    lv_obj_t *f = lv_obj_create(s);
    lv_obj_set_size(f, 90, 90);
    lv_obj_align(f, LV_ALIGN_CENTER, (i - 1) * 100, -10);
    lv_obj_set_style_bg_color(f, lv_color_hex(farben[i]), 0);
    lv_obj_set_style_border_width(f, 0, 0);
    lv_obj_t *l = lv_label_create(s);
    lv_label_set_text(l, namen[i]);
    lv_obj_align_to(l, f, LV_ALIGN_OUT_BOTTOM_MID, 0, 4);
  }
  lv_obj_t *v = lv_label_create(s);
  lv_label_set_text_fmt(v, "Fahrtenbuch-Display, Variante %s", FW_VARIANTE);
  lv_obj_align(v, LV_ALIGN_TOP_MID, 0, 8);
  uint32_t ende = millis() + 2500;
  while (millis() < ende) {
    lv_timer_handler();
    delay(5);
  }
  lv_obj_clean(s);
}

static void uiBauen() {
  hauptScreen = lv_screen_active();
  lv_obj_set_style_bg_color(hauptScreen, lv_color_hex(0x101418), 0);

  lv_obj_t *kopf = lv_obj_create(hauptScreen);
  lv_obj_set_size(kopf, BREITE, 22);
  lv_obj_align(kopf, LV_ALIGN_TOP_MID, 0, 0);
  lv_obj_set_style_radius(kopf, 0, 0);
  lv_obj_set_style_border_width(kopf, 0, 0);
  lv_obj_set_style_pad_all(kopf, 2, 0);
  lv_obj_set_style_bg_color(kopf, lv_color_hex(0x1E2A35), 0);
  lv_obj_remove_flag(kopf, LV_OBJ_FLAG_SCROLLABLE);
  kopfTitel = lv_label_create(kopf);
  lv_label_set_text(kopfTitel, "Fahrtenbuch");
  lv_obj_align(kopfTitel, LV_ALIGN_LEFT_MID, 4, 0);
  kopfStatus = lv_label_create(kopf);
  lv_obj_set_style_text_font(kopfStatus, &fb_font_12, 0);
  lv_obj_set_width(kopfStatus, 140);  // zwischen Titel (links) und Uhr (rechts), längere Texte mit "..."
  lv_label_set_long_mode(kopfStatus, LV_LABEL_LONG_DOT);
  lv_obj_set_style_text_align(kopfStatus, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_align(kopfStatus, LV_ALIGN_CENTER, 14, 0);
  lv_label_set_text(kopfStatus, "warte auf Server ...");
  kopfUhr = lv_label_create(kopf);
  lv_label_set_text(kopfUhr, "--:--");
  lv_obj_align(kopfUhr, LV_ALIGN_RIGHT_MID, -4, 0);

  tabview = lv_tabview_create(hauptScreen);
  lv_obj_set_size(tabview, BREITE, HOEHE - 22);
  lv_obj_align(tabview, LV_ALIGN_BOTTOM_MID, 0, 0);
  lv_tabview_set_tab_bar_position(tabview, LV_DIR_BOTTOM);
  lv_tabview_set_tab_bar_size(tabview, 30);
  lv_obj_set_style_text_font(lv_tabview_get_tab_bar(tabview), &fb_font_12, 0);
  seiteUebersicht = spalte(lv_tabview_add_tab(tabview, "Übersicht"));
  seiteFahrten = spalte(lv_tabview_add_tab(tabview, "Fahrten"));
  seiteRekorde = spalte(lv_tabview_add_tab(tabview, "Rekorde"));
  seiteMehr = spalte(lv_tabview_add_tab(tabview, "Mehr"));

  // Übersicht
  autoListe = lv_obj_create(seiteUebersicht);
  lv_obj_set_size(autoListe, LV_PCT(100), LV_SIZE_CONTENT);
  spalte(autoListe);
  lv_obj_set_style_pad_all(autoListe, 0, 0);
  lv_obj_set_style_border_width(autoListe, 0, 0);
  lv_obj_set_style_bg_opa(autoListe, LV_OPA_TRANSP, 0);
  laeuftLabel = lv_label_create(seiteUebersicht);
  lv_obj_set_width(laeuftLabel, LV_PCT(100));
  lv_label_set_long_mode(laeuftLabel, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_color(laeuftLabel, lv_palette_main(LV_PALETTE_AMBER), 0);
  lv_label_set_text(laeuftLabel, "");

  // Fahrten
  fahrtenListe = lv_list_create(seiteFahrten);
  lv_obj_set_size(fahrtenListe, LV_PCT(100), LV_PCT(100));
  lv_obj_set_style_text_font(fahrtenListe, &fb_font_12, 0);

  // Rekorde
  lv_obj_t *wahl = lv_obj_create(seiteRekorde);
  lv_obj_set_size(wahl, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(wahl, LV_FLEX_FLOW_ROW);
  lv_obj_set_style_pad_all(wahl, 0, 0);
  lv_obj_set_style_pad_column(wahl, 4, 0);
  lv_obj_set_style_border_width(wahl, 0, 0);
  lv_obj_set_style_bg_opa(wahl, LV_OPA_TRANSP, 0);
  static const char *zeitraeume[] = {"monat", "jahr", "gesamt"};
  static const char *zeitraumNamen[] = {"Monat", "Jahr", "Gesamt"};
  for (int i = 0; i < 3; i++) {
    lv_obj_t *b = lv_button_create(wahl);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CHECKABLE);
    lv_obj_set_flex_grow(b, 1);
    lv_obj_add_event_cb(b, rekordWahl, LV_EVENT_CLICKED, (void *)zeitraeume[i]);
    lv_obj_t *l = lv_label_create(b);
    lv_label_set_text(l, zeitraumNamen[i]);
    lv_obj_center(l);
    rekordKnoepfe[i] = b;
  }
  lv_obj_add_state(rekordKnoepfe[0], LV_STATE_CHECKED);
  rekordLabel = lv_label_create(seiteRekorde);
  lv_obj_set_width(rekordLabel, LV_PCT(100));
  lv_label_set_long_mode(rekordLabel, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_font(rekordLabel, &fb_font_12, 0);
  lv_label_set_text(rekordLabel, "Noch keine Daten.");

  // Mehr
  lv_obj_t *t = lv_label_create(seiteMehr);
  lv_obj_set_style_text_font(t, &fb_font_20, 0);
  lv_label_set_text(t, "Kosten");
  kostenLabel = lv_label_create(seiteMehr);
  lv_obj_set_width(kostenLabel, LV_PCT(100));
  lv_label_set_long_mode(kostenLabel, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_font(kostenLabel, &fb_font_12, 0);
  lv_label_set_text(kostenLabel, "-");
  t = lv_label_create(seiteMehr);
  lv_obj_set_style_text_font(t, &fb_font_20, 0);
  lv_label_set_text(t, "Server");
  serverLabel = lv_label_create(seiteMehr);
  lv_obj_set_width(serverLabel, LV_PCT(100));
  lv_label_set_long_mode(serverLabel, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_font(serverLabel, &fb_font_12, 0);
  lv_label_set_text(serverLabel, "-");
  lv_obj_t *qrZeile = lv_obj_create(seiteMehr);
  lv_obj_set_size(qrZeile, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(qrZeile, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(qrZeile, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_all(qrZeile, 4, 0);
  lv_obj_set_style_border_width(qrZeile, 0, 0);
  lv_obj_set_style_bg_opa(qrZeile, LV_OPA_TRANSP, 0);
  qr = lv_qrcode_create(qrZeile);
  lv_qrcode_set_size(qr, 96);
  lv_qrcode_set_dark_color(qr, lv_color_black());
  lv_qrcode_set_light_color(qr, lv_color_white());
  lv_obj_set_style_border_color(qr, lv_color_white(), 0);
  lv_obj_set_style_border_width(qr, 4, 0);
  urlLabel = lv_label_create(qrZeile);
  lv_obj_set_flex_grow(urlLabel, 1);
  lv_label_set_long_mode(urlLabel, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_font(urlLabel, &fb_font_12, 0);
  lv_label_set_text(urlLabel, "Adresse für die Apps:\n-");
  lv_obj_t *m = lv_button_create(seiteMehr);
  lv_obj_set_size(m, LV_PCT(100), 36);
  lv_obj_set_style_bg_color(m, lv_palette_main(LV_PALETTE_RED), 0);
  lv_obj_add_event_cb(m, menueOeffnen, LV_EVENT_CLICKED, nullptr);
  lv_obj_t *ml = lv_label_create(m);
  lv_label_set_text(ml, LV_SYMBOL_POWER " Server-Menü");
  lv_obj_center(ml);
  lv_obj_t *fw = lv_label_create(seiteMehr);
  lv_obj_set_style_text_font(fw, &fb_font_12, 0);
  lv_label_set_text_fmt(fw, "Firmware display-%d, Protokoll %d, Variante %s", FW_BUILD, FW_PROTOKOLL, FW_VARIANTE);
}

// ---------------------------------------------------------------------------
// Nachrichten der Bridge
// ---------------------------------------------------------------------------

static void zeigeAutos(JsonArray d) {
  lv_obj_clean(autoListe);
  autoIds.clear();
  size_t i = 0;
  for (JsonObject a : d) {
    autoIds.push_back(String((const char *)(a["id"] | "")));
    lv_obj_t *karte = lv_obj_create(autoListe);
    lv_obj_set_size(karte, LV_PCT(100), LV_SIZE_CONTENT);
    lv_obj_set_style_pad_all(karte, 4, 0);
    lv_obj_set_style_pad_left(karte, 10, 0);
    lv_obj_set_style_border_side(karte, LV_BORDER_SIDE_LEFT, 0);
    lv_obj_set_style_border_width(karte, 6, 0);
    lv_obj_set_style_border_color(karte, farbe(a["farbe"] | "#888888"), 0);
    lv_obj_add_flag(karte, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_remove_flag(karte, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_event_cb(karte, autoAngetippt, LV_EVENT_CLICKED, (void *)i);

    lv_obj_t *name = lv_label_create(karte);
    lv_label_set_text(name, a["name"] | "?");
    lv_obj_align(name, LV_ALIGN_TOP_LEFT, 0, 0);
    lv_obj_t *km = lv_label_create(karte);
    String t = "Monat " + tausender(ganz(a["km_monat"])) + " km   Jahr " + tausender(ganz(a["km_jahr"])) + " km";
    lv_label_set_text(km, t.c_str());
    lv_obj_set_style_text_font(km, &fb_font_12, 0);
    lv_obj_align(km, LV_ALIGN_TOP_LEFT, 0, 18);
    lv_obj_t *stand = lv_label_create(karte);
    String s = "Stand " + tausender(ganz(a["km"])) + " km" + ((a["elektro"] | false) ? "   (E-Auto)" : "");
    lv_label_set_text(stand, s.c_str());
    lv_obj_set_style_text_font(stand, &fb_font_12, 0);
    lv_obj_set_style_text_color(stand, lv_color_hex(0x9AA5B1), 0);
    lv_obj_align(stand, LV_ALIGN_TOP_LEFT, 0, 34);
    i++;
  }
  if (i == 0) lv_label_set_text(lv_label_create(autoListe), "Keine Autos.");
}

static void zeigeLaeuft(JsonArray d) {
  String t;
  for (JsonObject f : d) {
    if (t.length()) t += "\n";
    t += String("Unterwegs: ") + (const char *)(f["fahrer"] | "?") + " im " + (const char *)(f["auto"] | "?") +
         " seit " + (const char *)(f["seit"] | "");
  }
  lv_label_set_text(laeuftLabel, t.c_str());
}

static void zeigeFahrten(JsonArray d) {
  lv_obj_clean(fahrtenListe);
  fahrtDetails.clear();
  size_t i = 0;
  for (JsonObject f : d) {
    String zeile1 = String((const char *)(f["datum"] | "")) + " " + (const char *)(f["fahrer"] | "") + ", " +
                    (const char *)(f["auto"] | "");
    String zeile2 = kommazahl(f["km"] | 0.0f, 1) + " km, " + String(ganz(f["min"])) + " min";
    String von = f["von"] | "", nach = f["nach"] | "";
    if (von.length() || nach.length()) zeile2 += ", " + von + " → " + nach;
    lv_obj_t *b = lv_list_add_button(fahrtenListe, nullptr, (zeile1 + "\n" + zeile2).c_str());
    lv_obj_add_event_cb(b, fahrtAngetippt, LV_EVENT_CLICKED, (void *)i);

    String det = zeile1 + "\n" + String((const char *)(f["zeit"] | "")) + " Uhr, " + zeile2;
    long maxKmh = ganz(f["max"]), schnitt = ganz(f["schnitt"]);
    if (maxKmh > 0) det += "\nHöchstens " + String(maxKmh) + " km/h, Schnitt " + String(schnitt) + " km/h";
    String zweck = f["zweck"] | "";
    if (zweck.length()) det += "\nZweck: " + zweck;
    fahrtDetails.push_back(det);
    i++;
  }
  if (i == 0) lv_list_add_text(fahrtenListe, "Noch keine Fahrten.");
}

static void zeigeRekorde(JsonDocument &doc) {
  String zr = doc["zeitraum"] | "monat";
  for (int i = 0; i < 3; i++) lv_obj_remove_state(rekordKnoepfe[i], LV_STATE_CHECKED);
  lv_obj_add_state(rekordKnoepfe[zr == "jahr" ? 1 : (zr == "gesamt" ? 2 : 0)], LV_STATE_CHECKED);
  String t = String(ganz(doc["fahrten"])) + " Fahrten, " + tausender(ganz(doc["km"])) + " km\n\nLängste Fahrten:\n";
  int n = 1;
  for (JsonObject f : doc["d"].as<JsonArray>()) {
    t += String(n++) + ". " + kommazahl(f["km"] | 0.0f, 1) + " km  " + (const char *)(f["fahrer"] | "") + ", " +
         (const char *)(f["auto"] | "") + " (" + (const char *)(f["datum"] | "") + ")\n";
  }
  if (n == 1) t += "-\n";
  JsonArray proAuto = doc["pro_auto"].as<JsonArray>();
  if (proAuto.size()) {
    t += "\nLängste pro Auto:\n";
    for (JsonObject a : proAuto) {
      t += String((const char *)(a["auto"] | "")) + ": " + kommazahl(a["km"] | 0.0f, 1) + " km\n";
    }
  }
  if (!doc["schnellste"].isNull()) {
    t += "\nSchnellste: " + String(ganz(doc["schnellste"]["kmh"])) + " km/h, " +
         (const char *)(doc["schnellste"]["fahrer"] | "") + " im " + (const char *)(doc["schnellste"]["auto"] | "");
  }
  if (!doc["meiste_km"].isNull()) {
    t += "\nMeiste km: " + String((const char *)(doc["meiste_km"]["fahrer"] | "")) + " (" +
         tausender(ganz(doc["meiste_km"]["km"])) + " km)";
  }
  lv_label_set_text(rekordLabel, t.c_str());
}

static String kostenText(JsonObject k) {
  String einheit = k["einheit"] | "L";
  String t = String((const char *)(k["auto"] | "?")) + "\n";
  t += "  " + String(k["elektro"] | false ? "Laden" : "Tanken") + ": Monat " + euro(k["euro_monat"] | 0.0f) +
       ", Jahr " + euro(k["euro_jahr"] | 0.0f) + "\n";
  float sonstJahr = k["sonst_jahr"] | 0.0f;
  if (sonstJahr > 0) t += "  Sonstiges im Jahr: " + euro(sonstJahr) + "\n";
  if (k["elektro"] | false) {
    float zuhause = k["kwh_zuhause_monat"] | 0.0f, oeffentlich = k["kwh_oeffentlich_monat"] | 0.0f;
    if (zuhause > 0 || oeffentlich > 0) {
      t += "  Monat: zuhause " + kommazahl(zuhause, 1) + " kWh (kostenlos), unterwegs " + kommazahl(oeffentlich, 1) +
           " kWh\n";
    }
    if (!k["anteil_zuhause"].isNull()) t += "  Zuhause geladen im Jahr: " + String(ganz(k["anteil_zuhause"])) + " %\n";
  }
  if (!k["l100"].isNull()) t += "  Verbrauch: " + kommazahl(k["l100"] | 0.0f, 1) + " " + einheit + "/100 km\n";
  if (!k["euro_km"].isNull()) t += "  Pro km: " + euro(k["euro_km"] | 0.0f) + "\n";
  return t;
}

static void zeigeKosten(JsonArray d) {
  String t;
  for (JsonObject k : d) t += kostenText(k);
  lv_label_set_text(kostenLabel, t.length() ? t.c_str() : "-");
}

static void zeigeServer(JsonDocument &doc) {
  bool ok = doc["ok"] | false;
  String t = String(ok ? "Läuft" : "PocketBase antwortet nicht");
  t += ", Version " + String((const char *)(doc["version"] | "?"));
  if (!doc["temp"].isNull()) t += "\nTemperatur " + kommazahl(doc["temp"] | 0.0f, 1) + " °C";
  if (!doc["uptime_h"].isNull()) {
    long h = ganz(doc["uptime_h"]);
    t += ", läuft seit " + (h >= 48 ? String(h / 24) + " Tagen" : String(h) + " h");
  }
  if (!doc["frei_gb"].isNull()) t += "\nFrei: " + String(ganz(doc["frei_gb"])) + " GB";
  String b = doc["backup"] | "";
  t += "\nLetztes Backup: " + (b.length() ? b : String("-"));
  String warnung = doc["backup_warnung"] | "";
  if (warnung.length()) t += "\nAchtung: " + warnung;
  lv_label_set_text(serverLabel, t.c_str());
  // Kopfzeile: PocketBase aus (rot) hat Vorrang vor einer Backup-Warnung (gelb).
  if (!ok) {
    lv_label_set_text(kopfStatus, "PocketBase aus");
    lv_obj_set_style_text_color(kopfStatus, lv_palette_main(LV_PALETTE_RED), 0);
  } else if (warnung.length()) {
    lv_label_set_text(kopfStatus, "Backup prüfen");
    lv_obj_set_style_text_color(kopfStatus, lv_palette_main(LV_PALETTE_AMBER), 0);
  } else {
    lv_label_set_text(kopfStatus, "");
  }

  String url = doc["url"] | "";
  if (url.length() && url != appUrl) {
    appUrl = url;
    lv_qrcode_update(qr, appUrl.c_str(), appUrl.length());
    lv_label_set_text(urlLabel, ("Adresse für die Apps:\n" + appUrl).c_str());
  }
}

static void zeigeDetails(JsonDocument &doc) {
  if (!detailScreen) return;
  lv_label_set_text(detailTitel, doc["name"] | "?");
  int maxKm = 10;
  JsonArray monate = doc["monate"].as<JsonArray>();
  for (JsonObject m : monate) maxKm = max(maxKm, (int)ganz(m["km"]));
  lv_chart_set_range(detailChart, LV_CHART_AXIS_PRIMARY_Y, 0, maxKm);
  int i = 0;
  String erster, letzter;
  for (JsonObject m : monate) {
    lv_chart_set_value_by_id(detailChart, detailSerie, i++, (int32_t)ganz(m["km"]));
    if (erster.isEmpty()) erster = (const char *)(m["m"] | "");
    letzter = (const char *)(m["m"] | "");
  }
  lv_chart_refresh(detailChart);
  String t = "km pro Monat " + erster + " bis " + letzter + ", höchstens " + tausender(maxKm) + " km\n";
  t += String(ganz(doc["fahrten_jahr"])) + " Fahrten in diesem Jahr\n";
  for (JsonObject f : doc["fahrer"].as<JsonArray>()) {
    t += "  " + String((const char *)(f["name"] | "")) + ": " + tausender(ganz(f["km"])) + " km\n";
  }
  if (!doc["kosten"].isNull()) t += kostenText(doc["kosten"].as<JsonObject>());
  lv_label_set_text(detailText, t.c_str());
}

static void uhrSetzen(const char *iso) {
  // "2026-11-02T14:32:00+01:00"
  int j, mo, ta, h, mi, s;
  if (sscanf(iso, "%d-%d-%dT%d:%d:%d", &j, &mo, &ta, &h, &mi, &s) != 6) return;
  uhrSekundeDesTages = h * 3600L + mi * 60 + s;
  uhrMillis = millis();
  // Wochentag nach Sakamoto, 0 = Sonntag -> auf Montag = 0 umrechnen
  static const int tab[] = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};
  int y = j - (mo < 3);
  int w = (y + y / 4 - y / 100 + y / 400 + tab[mo - 1] + ta) % 7;
  uhrWochentag = (w + 6) % 7;
}

static int stundeJetzt() {
  if (uhrSekundeDesTages < 0) return 12;
  long sek = (uhrSekundeDesTages + (millis() - uhrMillis) / 1000) % 86400;
  return sek / 3600;
}

static void uhrZeichnen() {
  if (uhrSekundeDesTages < 0) return;
  uint32_t vergangen = (millis() - uhrMillis) / 1000;
  long sek = uhrSekundeDesTages + vergangen;
  int tage = sek / 86400;
  sek %= 86400;
  static const char *wt[] = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
  char text[16];
  snprintf(text, sizeof(text), "%s %02ld:%02ld", wt[(uhrWochentag + tage) % 7], sek / 3600, (sek / 60) % 60);
  lv_label_set_text(kopfUhr, text);
}

static uint32_t ausSeit = 0;

// Zuletzt angezeigte Nachricht je Typ (siehe verarbeiten). Wird bei Funkstille geleert,
// damit nach der Rückkehr der Bridge alles wieder gezeichnet wird.
static const int ZWISCHENSPEICHER = 6;
static const char *gleicheUeberspringen[ZWISCHENSPEICHER] = {"autos", "laeuft", "fahrten", "rekorde", "kosten", "server"};
static String letzteZeile[ZWISCHENSPEICHER];

static void zwischenspeicherLeeren() {
  for (auto &z : letzteZeile) z = "";
}

static void ausschaltenEntfernen() {
  if (!ausOverlay) return;
  lv_obj_delete(ausOverlay);
  ausOverlay = nullptr;
}

static void ausschaltenAnzeigen() {
  ausSeit = millis();
  if (ausOverlay) return;
  ausOverlay = lv_obj_create(lv_layer_top());
  lv_obj_set_size(ausOverlay, BREITE, HOEHE);
  lv_obj_set_style_bg_color(ausOverlay, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(ausOverlay, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(ausOverlay, 0, 0);
  lv_obj_t *l = lv_label_create(ausOverlay);
  lv_obj_set_style_text_font(l, &fb_font_20, 0);
  lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
  lv_label_set_text(l, "Pi fährt herunter.\n\nIn etwa 30 Sekunden kann\nder Stecker gezogen werden.");
  lv_obj_center(l);
}

// ---------------------------------------------------------------------------
// Firmware-Update über die serielle Schnittstelle (vom Pi, ohne Knöpfe)
// ---------------------------------------------------------------------------
// Pi:  {"t":"update","groesse":N,"md5":"<32 hex>"}
// ESP: {"t":"update","status":"bereit"}
// Pi:  Rohdaten in Blöcken zu 1024 Byte (letzter kürzer), nach jedem Block
// ESP: {"t":"update","status":"ack","bytes":<gesamt>}
// Ende: MD5 stimmt -> {"t":"update","status":"ok"} und Neustart,
//       sonst {"t":"update","status":"fehler","text":"..."} (alte Firmware bleibt aktiv).
// Geschrieben wird in den zweiten App-Slot, erst nach erfolgreicher Prüfung wird umgeschaltet.

static Stream *quelle = nullptr;  // Schnittstelle, von der die aktuelle Nachricht kam
static lv_obj_t *updOverlay = nullptr, *updLabel = nullptr, *updBalken = nullptr;

static void updateAntwort(Stream &s, const char *status, const char *text = nullptr, long bytes = -1) {
  JsonDocument doc;
  doc["t"] = "update";
  doc["status"] = status;
  if (text) doc["text"] = text;
  if (bytes >= 0) doc["bytes"] = bytes;
  String zeile;
  serializeJson(doc, zeile);
  zeile += '\n';
  s.print(zeile);
  s.flush();
}

static void updateAnzeige(const String &text, int prozent) {
  if (!updOverlay) {
    updOverlay = lv_obj_create(lv_layer_top());
    lv_obj_set_size(updOverlay, BREITE, HOEHE);
    lv_obj_set_style_bg_color(updOverlay, lv_color_black(), 0);
    lv_obj_set_style_bg_opa(updOverlay, LV_OPA_COVER, 0);
    lv_obj_set_style_radius(updOverlay, 0, 0);
    lv_obj_set_style_border_width(updOverlay, 0, 0);
    updLabel = lv_label_create(updOverlay);
    lv_obj_set_style_text_font(updLabel, &fb_font_20, 0);
    lv_obj_set_style_text_align(updLabel, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(updLabel, LV_ALIGN_CENTER, 0, -30);
    updBalken = lv_bar_create(updOverlay);
    lv_obj_set_size(updBalken, 260, 16);
    lv_obj_align(updBalken, LV_ALIGN_CENTER, 0, 30);
    lv_bar_set_range(updBalken, 0, 100);
  }
  lv_label_set_text(updLabel, text.c_str());
  lv_bar_set_value(updBalken, prozent, LV_ANIM_OFF);
  lv_timer_handler();
}

static void updateFehler(Stream &s, const char *grund) {
  Update.abort();
  updateAntwort(s, "fehler", grund);
  updateAnzeige(String("Update fehlgeschlagen:\n") + grund + "\nAlte Firmware bleibt aktiv.", 0);
  uint32_t ende = millis() + 4000;
  while (millis() < ende) {
    lv_timer_handler();
    delay(5);
  }
  lv_obj_delete(updOverlay);
  updOverlay = nullptr;
}

static void updateEmpfangen(JsonDocument &doc, Stream &s) {
  size_t groesse = (size_t)ganz(doc["groesse"]);
  String md5 = doc["md5"] | "";
  updateAnzeige("Display-Update\nwird empfangen ...", 0);
  if (groesse == 0 || md5.length() != 32) return updateFehler(s, "Größe oder MD5 fehlt");
  if (!Update.begin(groesse)) return updateFehler(s, Update.errorString());
  Update.setMD5(md5.c_str());
  updateAntwort(s, "bereit");

  static uint8_t block[1024];
  size_t fertig = 0;
  int letzteProzent = -1;
  while (fertig < groesse) {
    size_t soll = min(sizeof(block), groesse - fertig), da = 0;
    // Gehen auf der Leitung Bytes verloren, bleibt der Block unvollständig. Dann den Rest
    // verwerfen und denselben Block mit {"status":"nochmal","bytes":<bisher>} neu anfordern.
    for (int versuch = 0;; versuch++) {
      da = 0;
      bool unvollstaendig = false;
      uint32_t t0 = millis();
      while (da < soll) {
        if (s.available()) {
          block[da++] = (uint8_t)s.read();
          t0 = millis();
        } else if (millis() - t0 > 1500) {
          unvollstaendig = true;
          break;
        }
      }
      if (!unvollstaendig) break;
      if (versuch >= 5) return updateFehler(s, "Zeitüberschreitung");
      uint32_t ruhig = millis();
      while (millis() - ruhig < 150) {  // verspätete Reste abwarten und wegwerfen
        if (s.available()) {
          s.read();
          ruhig = millis();
        }
      }
      updateAntwort(s, "nochmal", nullptr, (long)fertig);
    }
    if (Update.write(block, da) != da) return updateFehler(s, Update.errorString());
    fertig += da;
    updateAntwort(s, "ack", nullptr, (long)fertig);
    int prozent = (int)(fertig * 100 / groesse);
    if (prozent != letzteProzent) {
      letzteProzent = prozent;
      updateAnzeige("Display-Update\n" + String(prozent) + " %", prozent);
    }
  }
  if (!Update.end(true)) return updateFehler(s, Update.errorString());
  updateAntwort(s, "ok");
  updateAnzeige("Update fertig,\nstarte neu ...", 100);
  delay(500);
  ESP.restart();
}

static void verarbeiten(const String &zeile) {
  if (zeile.length() < 2 || zeile[0] != '{') return;  // Bootmeldungen und Rauschen ignorieren
  JsonDocument doc;
  if (deserializeJson(doc, zeile)) return;
  const char *t = doc["t"] | "";
  letzteNachricht = millis();
  datenDa = true;

  // Läuft der Pi nach "aus" doch weiter (Ausschalten fehlgeschlagen), die Meldung wieder wegnehmen.
  if (ausOverlay && strcmp(t, "aus") && millis() - ausSeit > 60000) ausschaltenEntfernen();

  // Die Bridge schickt alle 30 s alles neu. Unveränderte Listen nicht neu aufbauen,
  // sonst springt eine gerade gescrollte Liste jedes Mal nach oben.
  for (int i = 0; i < ZWISCHENSPEICHER; i++) {
    if (!strcmp(t, gleicheUeberspringen[i])) {
      if (zeile == letzteZeile[i]) return;
      letzteZeile[i] = zeile;
      break;
    }
  }

  if (!strcmp(t, "autos")) zeigeAutos(doc["d"].as<JsonArray>());
  else if (!strcmp(t, "laeuft")) zeigeLaeuft(doc["d"].as<JsonArray>());
  else if (!strcmp(t, "fahrten")) zeigeFahrten(doc["d"].as<JsonArray>());
  else if (!strcmp(t, "rekorde")) zeigeRekorde(doc);
  else if (!strcmp(t, "kosten")) zeigeKosten(doc["d"].as<JsonArray>());
  else if (!strcmp(t, "server")) zeigeServer(doc);
  else if (!strcmp(t, "details")) zeigeDetails(doc);
  else if (!strcmp(t, "zeit")) uhrSetzen(doc["iso"] | "");
  else if (!strcmp(t, "aus")) ausschaltenAnzeigen();
  else if (!strcmp(t, "update") && quelle) updateEmpfangen(doc, *quelle);
  else if (!strcmp(t, "einstellung")) {
    if (!doc["drehung"].isNull()) {
      drehungSetzen((int)ganz(doc["drehung"]), true);
      drehLabelAktualisieren();
    }
  }
  else if (!strcmp(t, "ok")) {
    String was = doc["was"] | "";
    if (was == "pb_neustart") hinweis("Erledigt", "PocketBase wurde neu gestartet.");
    else if (was == "backup") hinweis("Erledigt", "Backup wurde angelegt.");
    else if (was == "reboot") hinweis("Neustart", "Der Pi startet neu, das dauert etwa eine Minute.");
    else if (was == "neu_laden") hinweis("Erledigt", "Daten wurden neu geladen.");
  } else if (!strcmp(t, "fehler")) {
    String was = doc["was"] | "";
    if (was == "poweroff") ausschaltenEntfernen();
    hinweis("Fehler", String((const char *)(doc["text"] | "unbekannt")));
  }
}

static void seriellLesen(Stream &s, String &puffer) {
  while (s.available()) {
    char c = (char)s.read();
    if (c == '\n') {
      String zeile = puffer;
      puffer = "";
      quelle = &s;
      verarbeiten(zeile);
    } else if (c != '\r') {
      if (puffer.length() < 16000) puffer += c;
      else puffer = "";
    }
  }
}

// ---------------------------------------------------------------------------
// Start und Schleife
// ---------------------------------------------------------------------------

void setup() {
  Serial.setRxBufferSize(8192);
  Serial.begin(115200);
  Serial2.setRxBufferSize(8192);
  Serial2.begin(115200, SERIAL_8N1, UART_RX, UART_TX);

  einstellungen.begin("fahrtenbuch", true);
  drehung = einstellungen.getUShort("drehung", 0) == 180 ? 180 : 0;
  einstellungen.end();

  tft.begin();
  tft.setRotation(tftRotation(drehung));  // 1 = quer, USB rechts; 3 = auf dem Kopf
  tft.fillScreen(TFT_BLACK);
  ledcSetup(BL_KANAL, 5000, 8);
  ledcAttachPin(BL_PIN, BL_KANAL);
  ledcWrite(BL_KANAL, 255);

  touchSpi.begin(TOUCH_CLK, TOUCH_MISO, TOUCH_MOSI, TOUCH_CS);
  touch.begin(touchSpi);
  touch.setRotation(1);  // bleibt fest, die 180°-Drehung spiegelt touchLesen()

  lv_init();
  lv_tick_set_cb(tick);
  lv_display_t *disp = lv_display_create(BREITE, HOEHE);
  lv_display_set_flush_cb(disp, flush);
  lv_display_set_buffers(disp, zeichenpuffer, nullptr, sizeof(zeichenpuffer), LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_indev_t *indev = lv_indev_create();
  lv_indev_set_type(indev, LV_INDEV_TYPE_POINTER);
  lv_indev_set_read_cb(indev, touchLesen);
  lvglBereit = true;

  farbtest();
  uiBauen();
  sendeHallo();
}

void loop() {
  lv_timer_handler();
  seriellLesen(Serial, lesepuffer[0]);
  seriellLesen(Serial2, lesepuffer[1]);

  static uint32_t letzterTakt = 0;
  uint32_t jetzt = millis();
  if (jetzt - letzterTakt >= 1000) {
    letzterTakt = jetzt;
    uhrZeichnen();

    // Bis Daten kommen alle 5 s "hallo", danach bei Funkstille alle 30 s
    bool still = !datenDa || jetzt - letzteNachricht > 90000;
    if (still && jetzt - letztesHallo > (datenDa ? 30000u : 5000u)) sendeHallo();
    if (datenDa && jetzt - letzteNachricht > 90000) {
      zwischenspeicherLeeren();
      lv_label_set_text(kopfStatus, "Server nicht erreichbar");
      lv_obj_set_style_text_color(kopfStatus, lv_palette_main(LV_PALETTE_RED), 0);
    }

    // Nach 2 Minuten ohne Berührung zurück zur Übersicht
    uint32_t inaktiv = lv_display_get_inactive_time(NULL);
    if (inaktiv > 120000) {
      if (lv_screen_active() != hauptScreen) lv_screen_load(hauptScreen);
      if (lv_tabview_get_tab_active(tabview) != 0) lv_tabview_set_active(tabview, 0, LV_ANIM_OFF);
    }

    // Nachts (22 bis 6 Uhr) gedimmt, bei Berührung 30 s lang hell
    int stunde = stundeJetzt();
    bool nacht = stunde >= 22 || stunde < 6;
    hintergrund(nacht && inaktiv > 30000 ? 20 : 255);
  }
  delay(5);
}
