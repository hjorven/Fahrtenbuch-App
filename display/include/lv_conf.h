/* LVGL-Einstellungen für das Fahrtenbuch-Display. Nicht gesetzte Werte kommen aus
 * lv_conf_internal.h (Standardwerte von LVGL 9.2). */
#ifndef LV_CONF_H
#define LV_CONF_H

#define LV_COLOR_DEPTH 16

/* Speicher über malloc der C-Bibliothek, kein fester LVGL-Pool */
#define LV_USE_STDLIB_MALLOC  LV_STDLIB_CLIB
#define LV_USE_STDLIB_STRING  LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF LV_STDLIB_CLIB

#define LV_USE_OS LV_OS_NONE
#define LV_DEF_REFR_PERIOD 33
#define LV_DPI_DEF 130

#define LV_USE_LOG 0
#define LV_USE_ASSERT_NULL 1
#define LV_USE_ASSERT_MALLOC 1

/* Eigene Schriften mit Umlauten (src/fonts) */
#define LV_FONT_MONTSERRAT_14 0
#define LV_FONT_CUSTOM_DECLARE LV_FONT_DECLARE(fb_font_12) LV_FONT_DECLARE(fb_font_14) LV_FONT_DECLARE(fb_font_20)
#define LV_FONT_DEFAULT &fb_font_14

#define LV_USE_QRCODE 1

#define LV_USE_THEME_DEFAULT 1
#define LV_THEME_DEFAULT_DARK 1

#define LV_BUILD_EXAMPLES 0
#define LV_BUILD_DEMOS 0

#endif
