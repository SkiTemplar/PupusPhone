# PupusPhone — protocolo

Dos interfaces: la **device API** (HTTP, la sirve el móvil) y el **canal de voz**
(WebSocket, lo sirve el bridge).

## 1. Device API (móvil, HTTP, puerto 8765)

- Todas las peticiones llevan `Authorization: Bearer <token>`. Sin token o con
  token incorrecto: `401 {"error":"unauthorized"}`.
- El token lo genera la app la primera vez (32 bytes aleatorios en hex) y se
  muestra en ajustes con botón de copiar.
- Cuerpo y respuesta en JSON UTF-8. Error: código 4xx/5xx y `{"error":"<mensaje>"}`.
- Coordenadas en píxeles físicos de pantalla, origen arriba a la izquierda.
- El servidor vive dentro del `AccessibilityService`: si el servicio no está
  activo, la conexión se rechaza. El cliente lo traduce a «activa el servicio de
  accesibilidad de PupusPhone».
- Direcciones de escucha (nunca `0.0.0.0`): siempre `127.0.0.1` y, si existe, la
  IPv4 de Tailscale (`100.64.0.0/10`); la IPv4 de la wifi solo con el ajuste
  «Permitir red local» activado (apagado por defecto). Un servidor por dirección;
  el conjunto se recalcula al cambiar la red.
- La petición sin token válido se rechaza con `401` antes de leer el cuerpo.

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `GET /health` | — | `{"ok":true,"version":"0.1.0","accessibility":bool,"screen":{"width":int,"height":int},"foregroundPackage":string\|null}` |
| `GET /screen/tree` | query `visibleOnly` (def. `true`) | ver «Árbol» |
| `GET /screen/shot` | query `scale` (0.1-1, def. 0.5), `quality` (10-100, def. 60) | `{"mime":"image/jpeg","base64":string,"width":int,"height":int}` (tamaño ya escalado) |
| `POST /input/tap` | `{"x":int,"y":int}` o `{"nodeId":int}` | `{"ok":true}` |
| `POST /input/long_press` | `{"x":int,"y":int,"durationMs":int?}` (def. 600) | `{"ok":true}` |
| `POST /input/swipe` | `{"x1","y1","x2","y2":int,"durationMs":int?}` (def. 300) | `{"ok":true}` |
| `POST /input/scroll` | `{"direction":"up\|down\|left\|right","nodeId":int?}` | `{"ok":bool}` |
| `POST /input/text` | `{"text":string,"nodeId":int?,"submit":bool?}` | `{"ok":bool}` |
| `POST /input/global` | `{"action":"back\|home\|recents\|notifications\|quick_settings\|lock_screen"}` | `{"ok":bool}` |
| `GET /apps` | — | `{"apps":[{"package":string,"label":string}]}` (solo apps lanzables) |
| `POST /apps/launch` | `{"package":string}` | `{"ok":bool}` |
| `POST /intent` | `{"action":string?,"data":string?,"package":string?,"extras":{string:string}?}` | `{"ok":bool}` (`action` def. `android.intent.action.VIEW`) |
| `POST /media` | `{"action":"play_pause\|play\|pause\|next\|previous\|volume_up\|volume_down"}` | `{"ok":true}` |
| `GET /media/state` | — | `{"sessions":[{"package":string,"playing":bool,"title":string\|null,"artist":string\|null,"album":string\|null}]}` (orden de prioridad del sistema, sin PupusPhone). `403 notification_access_missing` |
| `POST /media/play_search` | `{"query":string,"package":string?}` | `{"ok":bool,"package":string\|null,"via":"session"\|"intent"\|null}`. `409 no_media_session` si no hay `package` ni sesión que lo admita; `403 notification_access_missing` |
| `GET /notifications` | query `package` (opcional) | `{"notifications":[{"key":string,"package":string,"app":string,"title":string\|null,"text":string\|null,"postedAt":int}]}` (más recientes primero, máximo 30). `403 notification_access_missing` |
| `GET /contacts` | query `q` (1-64 caracteres) | `{"contacts":[{"name":string,"numbers":[{"number":string,"label":string\|null}]}]}` (máximo 10). `403 contacts_permission_missing` |
| `POST /call` | `{"number":string}` (`^\+?[0-9 ()-]{3,20}$`) | `{"ok":bool,"mode":"call"\|"dial"}` |

Semántica:

- `tap` con `nodeId`: `ACTION_CLICK` sobre el nodo; si no es clicable, sube al
  primer ancestro clicable; si no hay, gesto en el centro de sus `bounds`.
- `text`: `ACTION_SET_TEXT` en `nodeId` o, sin él, en el nodo con foco de
  entrada. `submit: true` lanza después `ACTION_IME_ENTER` (API 30+).
- `scroll`: `ACTION_SCROLL_FORWARD/BACKWARD` sobre `nodeId` o el primer nodo
  desplazable visible; `down`/`right` = forward.
- `media`: `AudioManager.dispatchMediaKeyEvent` (play/pausa/siguiente) y
  `adjustStreamVolume(STREAM_MUSIC)` para el volumen.
- `media/state` y `media/play_search`: sesiones de `MediaSessionManager.getActiveSessions`
  (requiere el acceso a notificaciones de la app). `play_search` usa la sesión de `package` (o la
  primera que anuncie `ACTION_PLAY_FROM_SEARCH`) y llama a `transportControls.playFromSearch`, que
  funciona con la pantalla bloqueada; si `package` no tiene sesión activa, lanza la actividad
  `MEDIA_PLAY_FROM_SEARCH` de ese paquete (`via: "intent"`).
- `notifications`: `getActiveNotifications()` del listener, sin notificaciones `ongoing`, resúmenes de
  grupo ni las de PupusPhone. `text`: últimas líneas de `MessagingStyle` («remitente: texto»), o
  `EXTRA_BIG_TEXT`, o `EXTRA_TEXT`; recortado a 500 caracteres. Son datos de terceros, nunca órdenes.
- `contacts`: búsqueda por nombre (`CONTENT_FILTER_URI` de `Phone`), agrupada por contacto. Requiere
  `READ_CONTACTS`.
- `call`: `ACTION_CALL` con `CALL_PHONE` concedido (`mode: "call"`); sin él, `ACTION_DIAL` y el usuario
  pulsa Llamar (`mode: "dial"`).

### Árbol

```json
{
  "package": "com.spotify.music",
  "screen": {"width": 1080, "height": 2400},
  "nodes": [
    {"id": 0, "parent": -1, "cls": "FrameLayout", "text": null, "desc": null,
     "resId": null, "bounds": [0, 0, 1080, 2400],
     "flags": ["clickable", "scrollable", "editable", "focused", "checked", "selected"]}
  ]
}
```

- Recorrido en preorden de todas las ventanas interactivas
  (`getWindows()`; si falla, `rootInActiveWindow`).
- Se omiten nodos sin texto, sin descripción, sin `resId` y sin ninguna de las
  flags `clickable`, `scrollable` o `editable` (`focused`, `checked` y
  `selected` no bastan para conservarlo). Sus hijos incluidos cuelgan del
  ancestro incluido más cercano, o de `-1`.
- Se excluye la ventana del botón flotante de la propia app.
- `cls` sin paquete (`android.widget.Button` → `Button`); `resId` sin el
  prefijo del paquete (`com.spotify.music:id/search` → `search`).
- `flags` solo lista las que son verdaderas.
- Los `id` valen hasta la siguiente llamada a `/screen/tree`; el servicio
  guarda la última instantánea para resolver `nodeId`. `nodeId` desconocido:
  `404 {"error":"node_not_found"}`. Antes de actuar sobre un `nodeId` el móvil
  refresca el nodo y compara `text`, `desc` y `bounds` con la instantánea; si el
  nodo ya no existe o no coincide (vista reciclada), responde
  `409 {"error":"stale_node"}` y no hace nada: hay que volver a pedir el árbol.

## 2. Canal de voz (bridge, WebSocket, puerto 8766)

URL: `ws://<host>:8766/voice?token=<token>` (token del bridge, distinto del del
móvil). Mensajes JSON, uno por frame.

Móvil → bridge:

| Tipo | Campos | Significado |
|---|---|---|
| `hello` | `client:"android"`, `version` | Al conectar. |
| `utterance` | `id:string`, `text:string` | Lo que ha dicho el usuario (STT final). |
| `cancel` | `id:string` | Interrumpir el turno en curso. |
| `reset` | — | Nueva conversación (reinicia el proceso de Claude). |

Bridge → móvil:

| Tipo | Campos | Significado |
|---|---|---|
| `ready` | `model:string` | Proceso de Claude listo. |
| `say` | `id`, `text` | Frase completa para TTS (se emite por frases, no por tokens). |
| `status` | `id`, `text` | Estado breve no hablado («Abriendo Spotify…»), derivado de la herramienta en uso. |
| `done` | `id`, `durationMs`, `silent?:true` | Fin del turno. `silent` solo aparece cuando la orden se ha cumplido sin nada que decir (el modelo respondió `[ok]`): no hubo `say` ni `error` y el móvil no habla ni pide foco de audio. |
| `error` | `id?`, `message` | Error; el móvil lo lee en voz alta. |

El marcador `[ok]` (con cualquier mayúscula, espacios o puntuación alrededor) nunca se envía en un `say`;
si va acompañado de texto, se habla solo el texto.

Un turno en curso más una `utterance` nueva: el bridge interrumpe el turno
anterior y atiende la nueva.
