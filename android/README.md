# PupusPhone — app Android

App del móvil: servicio de accesibilidad con la device API (HTTP, puerto 8765) y
cliente de voz (WebSocket al bridge, STT y TTS nativos es-ES). Contrato:
[`../docs/protocol.md`](../docs/protocol.md).

- Paquete `com.pupus.phone`, Kotlin, Views/XML sin AndroidX.
- minSdk 30 (Android 11), compile/target 35.
- Dependencias: NanoHTTPD 2.3.1 (servidor), OkHttp 4.12 (WebSocket), `org.json` del SDK.
- Toolchain: Gradle 8.14 (wrapper), AGP 8.11.1, Kotlin 2.1.21, JDK 17+ (el JBR 21 de Android Studio).

## Compilar

```bash
cd android
export JAVA_HOME="/c/Users/Rodrigo/AppData/Local/Programs/Android Studio/jbr"   # Git Bash
./gradlew testDebugUnitTest assembleDebug
```

`local.properties` (no versionado) debe tener `sdk.dir=C:/Users/<usuario>/AppData/Local/Android/Sdk`.
El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Puesta en marcha en el móvil

1. Abrir PupusPhone y conceder micrófono y notificaciones.
2. **Activar el servicio de accesibilidad**: Ajustes → Accesibilidad → Apps
   instaladas (o «Servicios descargados») → PupusPhone → activar. En Android 13+
   las apps instaladas fuera de Play muestran «Ajuste restringido»: Ajustes →
   Apps → PupusPhone → menú ⋮ → «Permitir ajustes restringidos», y volver a
   intentarlo. Al activarse, la device API escucha en el puerto 8765 de
   `127.0.0.1` y de la IP de Tailscale (si está conectado), y aparece el botón
   flotante. Ver «Red de la device API».
3. **Excluir de la optimización de batería**: botón «Excluir de la optimización
   de batería» en Ajustes de la app (abre la lista del sistema; elegir «Todas
   las apps» → PupusPhone → «No optimizar»). En MIUI/One UI, además, permitir
   inicio automático y actividad en segundo plano sin restricciones.
4. En Ajustes de la app: URL del bridge (`ws://<ip-tailscale-del-pc>:8766/voice`)
   y token del bridge. Copiar el token del dispositivo y configurarlo en el PC
   junto con la IP que muestra la app (la de Tailscale aparece como `tun0: 100.x.y.z`).
5. Activar «Modo coche»: arranca `VoiceService` (servicio en primer plano de tipo
   micrófono), conecta el WebSocket y mantiene la pantalla encendida.

Comprobación rápida desde el PC:

```bash
curl -H "Authorization: Bearer <token>" http://<ip-tailscale-movil>:8765/health
```

## Red de la device API

- Nunca escucha en `0.0.0.0`. Por defecto, un servidor en `127.0.0.1` (para
  Termux, fase 2) y otro en la IPv4 de Tailscale (`100.64.0.0/10`, interfaz
  `tun0`) si existe.
- Ajuste «Permitir red local» (apagado por defecto): añade la IPv4 de la wifi
  (`wlan*`). La API va en HTTP sin cifrar; activarlo solo en redes de confianza.
- Con `ConnectivityManager.NetworkCallback` se recalculan las direcciones al
  conectar o desconectar Tailscale o la wifi, sin reiniciar el servicio.
- El token se comprueba antes de leer el cuerpo y hay un máximo de 4 hilos
  atendiendo peticiones (16 en cola; el resto se cierra).

## Arquitectura

| Pieza | Fichero |
|---|---|
| Servicio de accesibilidad (servidor + overlay) | `PhoneAccessibilityService.kt` |
| Pegamento NanoHTTPD, un servidor por dirección | `api/DeviceApiServer.kt`, `api/DeviceApiHost.kt`, `api/BoundedAsyncRunner.kt` |
| Política de direcciones (pura, testeada) | `api/BindPolicy.kt` |
| Enrutado, token y validación (puros, testeados) | `api/RouteHandler.kt`, `api/Requests.kt`, `api/DeviceToken.kt` |
| Operaciones sobre el sistema | `device/AndroidDeviceController.kt`, `Gestures.kt`, `ScreenCapture.kt`, `SystemActions.kt` |
| Reglas del árbol y nodos obsoletos (puras, testeadas) | `tree/TreeSerializer.kt`, `tree/A11yNode.kt`, `tree/NodeFreshness.kt` |
| Voz | `voice/VoiceService.kt`, `BridgeClient.kt`, `SpeechIo.kt`, `VoiceMessages.kt`, `VoiceBus.kt`, `ReconnectPolicy.kt`, `UtteranceTracker.kt` |
| UI | `ui/MainActivity.kt`, `ui/OverlayButton.kt` |

Interpretaciones del contrato:

- Árbol: un nodo sin texto, descripción, `resId` ni flag interactiva
  (`clickable`, `scrollable`, `editable`) nunca se emite; sus descendientes
  incluidos cuelgan del ancestro incluido más cercano (o de `-1`). `focused`,
  `checked` y `selected` se listan, pero por sí solas no conservan un nodo. Se
  excluye la ventana del propio overlay. Con `visibleOnly=true` se poda el
  subárbol de un nodo no visible.
- Errores adicionales: `400 {"error":"bad_request: <detalle>"}` (validación),
  `404 not_found` (ruta), `405 method_not_allowed`, `413 body_too_large`,
  `500 internal_error: …`, `503 screenshot_failed: code N`, `504 gesture_timeout`
  o `screenshot_timeout`, `409 gesture_cancelled`, `409 stale_node`.
- `POST /intent` exige al menos uno de `action`, `data` o `package`.

## Voz y arranque en segundo plano

Flujo: toque → una frase (SpeechRecognizer es-ES) → `utterance` → TTS de cada
`say` en cola → `done` → en espera. Un toque mientras piensa o habla corta el TTS,
envía `cancel` y escucha. Un toque mientras escucha cancela la escucha. Los
`error` se leen en voz alta.

Reconexión al bridge con backoff exponencial (1-30 s) que solo se reinicia al
recibir `ready`; un cierre 1008 o un 401/403 en el handshake (token del bridge
incorrecto) detiene los reintentos y se muestra en la UI. Si el TTS no arranca,
la cola se vacía, el turno termina y el error aparece en el estado.

Restricciones de Android que condicionan el diseño:

- Desde Android 14 un servicio en primer plano de tipo micrófono solo puede
  arrancarse con el permiso concedido y desde un estado «en uso» (actividad
  visible o exenciones). Además, SpeechRecognizer necesita que la app tenga
  acceso al micrófono «mientras se usa», que fuera de la actividad solo da ese
  servicio en primer plano.
- Por eso el camino robusto es: **activar «Modo coche» desde la actividad**. El
  servicio queda vivo y el botón flotante (overlay de accesibilidad, ventana de
  la propia app) solo llama al servicio ya arrancado; no inicia nada desde
  segundo plano.
- Si se pulsa el overlay con el servicio parado, se intenta arrancarlo
  (`startForegroundService`). Los servicios de accesibilidad activos suelen
  tener exención de arranque en segundo plano, pero no está verificado en el
  dispositivo; si Android lo rechaza, se captura la excepción y el estado dice
  «abre la app y activa el modo coche».

## Limitaciones conocidas

- Sin probar aún en un dispositivo real (fase 0): solo tests JVM y compilación.
- La device API va en HTTP plano con token bearer; por eso solo escucha en
  loopback y Tailscale salvo que se active «Permitir red local».
- La accesibilidad no desbloquea el móvil: con la pantalla bloqueada los gestos
  y el árbol no sirven. El modo coche mantiene la pantalla encendida con
  `FLAG_KEEP_SCREEN_ON` en la actividad y en el botón flotante, y la CPU con un
  wake lock parcial que se suelta al parar.
- `takeScreenshot` tiene un intervalo mínimo entre capturas (≈1 s); si se
  supera devuelve `503 screenshot_failed: code 3`. Las apps con `FLAG_SECURE`
  salen en negro.
- El STT nativo puede captar la música si suena alta; no se pide foco de audio
  ni se atenúa la música mientras se escucha o se habla.
- Algunos fabricantes matan servicios en segundo plano aunque estén exentos de
  la optimización de batería.
- Los `id` del árbol solo valen hasta la siguiente llamada a `/screen/tree`; si
  el nodo ha desaparecido o cambiado (texto, descripción o `bounds`), la acción
  devuelve `409 stale_node` en lugar de pulsar otra vista.
