# PupusPhone — plan

## Objetivo

Asistente de voz manos libres para el coche: se habla con Claude (suscripción de
Claude, vía Claude Code CLI) y Claude controla el móvil Android como el MCP de
Desktop controla el PC: lee la pantalla, pulsa, escribe, abre apps y deep links
(«ábreme Spotify y pon esta canción en la cola», «léeme los mensajes»).

## Decisiones (2026-11-10)

| Tema | Decisión | Motivo |
|---|---|---|
| Plataforma | Android | iOS no permite a una app controlar otras. |
| Control del móvil | App propia con `AccessibilityService` + servidor HTTP local | Lee el árbol de UI, gestos, texto, acciones globales y capturas (Android 11+) sin root ni ADB. |
| Cerebro | Claude Code CLI (`claude -p`, stream-json, proceso persistente) | Usa la suscripción; un proceso vivo evita el arranque en frío por turno. |
| Herramientas | Servidor MCP `pupusphone-mcp` (Node/TS, stdio) | Mismo patrón que Chrome/Desktop; también se puede añadir a las sesiones de Claude Code del PC. |
| Voz | STT y TTS nativos de Android (es-ES) | Sin coste ni latencia de red extra; la latencia la pone Claude. |
| Activación | Botón grande en la app; un toque = un turno | Elección de Rodrigo. |
| Red fase 1 | Tailscale entre PC (casa) y móvil (datos) | Sin abrir puertos; cifrado. |
| Portabilidad | Bridge y MCP en Node puro, sin dependencias nativas | Fase 2: el mismo código corre en Termux en el móvil. |

## Arquitectura

```text
Fase 1 (PC encendido)
  Móvil: app PupusPhone
    - UI: botón grande -> SpeechRecognizer -> WebSocket -> bridge
    - TTS lee las frases que devuelve el bridge
    - AccessibilityService + HTTP :8765 (device API, token)
          ^  HTTP por Tailscale
  PC:
    - bridge (Node) :8766 WebSocket de voz
        -> proceso persistente `claude -p --input-format stream-json`
             -> MCP stdio `pupusphone-mcp` -> device API del móvil

Fase 2 (PC apagado)
  Móvil: app PupusPhone + Termux (Node + Claude Code con la suscripción)
    bridge y MCP en Termux apuntando a 127.0.0.1
```

Contrato entre piezas: [protocol.md](protocol.md).

## Fases

### Fase 0 — infraestructura (hoy, sin móvil)

1. Plan y contrato (`docs/`).
2. App Android (`android/`): servicio de accesibilidad, device API, pantalla
   con botón, STT, TTS, cliente WebSocket, ajustes. Criterio: `assembleDebug` verde
   y tests unitarios del serializador del árbol.
3. PC (`pc/`): `mcp-server`, `bridge`, `fake-phone` (simulador de la device API).
   Criterio: typecheck y tests verdes; el MCP habla con `fake-phone`.

### Fase 1 — extremo a extremo con PC encendido (próxima sesión, con el móvil)

1. Instalar el APK (`adb install`), activar el servicio de accesibilidad,
   desactivar optimización de batería para la app.
2. Tailscale en PC y móvil; configurar URL y token en ambos lados.
3. Prueba con el MCP desde una sesión de Claude Code del PC
   («abre Spotify», «lee la pantalla»).
4. Prueba de voz completa en casa y luego en el coche.
5. Medir latencia por turno (objetivo: primera frase hablada < 4 s en órdenes
   simples) y ajustar modelo (Sonnet 5.5 por defecto, Haiku 5.5 para órdenes
   simples) y prompt.
6. Atajos: deep links y teclas multimedia antes que navegar por UI
   (`spotify:search:…`, `whatsapp://send?…`, `tel:`).

### Fase 2 — sin PC

1. Termux + Node + Claude Code en el móvil; login con la suscripción.
   Riesgo principal: que Claude Code no funcione estable en Termux. Plan B:
   host siempre encendido (Raspberry Pi o VPS) con el mismo bridge.
2. Bridge y MCP apuntando a `127.0.0.1`; la app arranca Termux por intent
   (`RUN_COMMAND`) o el usuario lo deja abierto.
3. Arranque automático al conectar el Bluetooth del coche (opcional).

## Riesgos conocidos

- Latencia: cada acción en pantalla es una ronda de herramienta (1-3 s). Se
  mitiga con deep links, teclas multimedia y un prompt que prioriza atajos.
- Pantalla bloqueada: la accesibilidad no desbloquea; la app mantiene la
  pantalla encendida mientras la conversación está activa (`FLAG_KEEP_SCREEN_ON`
  y wake lock en el servicio en primer plano).
- Términos de uso: la suscripción se usa a través de Claude Code CLI, uso
  personal; no se distribuye.
- Seguridad: la device API controla el móvil entero. Token obligatorio,
  escucha solo en Tailscale/loopback en producción, sin exponer a internet.
