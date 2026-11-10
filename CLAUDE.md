# CLAUDE.md — PupusPhone

## Que es

Asistente de voz manos libres para Android: la app del movil expone una device
API (AccessibilityService) y un boton de voz; en el PC, un bridge mantiene un
proceso de Claude Code CLI (suscripcion) con el MCP `pupusphone-mcp`, que
controla el movil. Plan: `docs/PLAN.md`. Contrato: `docs/protocol.md`.

## Estructura

- `android/`: app Kotlin (`com.pupus.phone`), Gradle wrapper, JDK de Android Studio.
- `pc/`: workspaces npm `mcp-server`, `bridge`, `fake-phone`. Node puro, sin
  dependencias nativas (fase 2 en Termux).

## Como compilar / ejecutar

- Android: ver `android/README.md` (`./gradlew assembleDebug`).
- PC: ver `pc/README.md` (`npm install`, `npm run typecheck`, `npm test`).

## Tests

- Android: `./gradlew testDebugUnitTest`.
- PC: `npm test` en `pc/` (usa `fake-phone`, no necesita el movil).
