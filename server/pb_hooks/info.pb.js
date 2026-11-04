/// <reference path="../pb_data/types.d.ts" />
// Adressen für die Apps (Roadmap Nr. 3): Nach dem Login holen die Apps hier, wie der Server
// noch erreichbar ist, und tragen fehlende Adressen selbst ein. Nur für angemeldete Benutzer.
// Werte aus docker-compose.yml (environment), sonst die üblichen Standardwerte.
routerAdd("GET", "/api/fahrtenbuch/info", (e) => {
  const wert = (name, standard) => {
    const v = $os.getenv(name);
    return v ? v : standard;
  };
  return e.json(200, {
    heimnetz: wert("FB_HEIMNETZ", "http://fahrtenbuch.local:8090"),
    heimnetz_ip: wert("FB_HEIMNETZ_IP", ""),
    tailscale: wert("FB_TAILSCALE", ""),
    tailscale_ip: wert("FB_TAILSCALE_IP", ""),
    downloads: wert("FB_DOWNLOADS", "http://fahrtenbuch.local:8091"),
  });
}, $apis.requireAuth());
