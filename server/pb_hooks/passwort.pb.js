/// <reference path="../pb_data/types.d.ts" />
// Wer sein Passwort über die App ändert, hat danach kein Start-Passwort mehr:
// users.passwort_aendern zurücksetzen (siehe Migration 1762500000_passwort_aendern.js).
// Benutzer dürfen das Feld nicht selbst auf false setzen, ohne das Passwort zu ändern.
onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) {
    const body = e.requestInfo().body || {};
    if (body.password) {
      e.record.set("passwort_aendern", false);
    } else {
      e.record.set("passwort_aendern", e.record.original().getBool("passwort_aendern"));
    }
  }
  e.next();
}, "users");
