# PupusPhone — PC

Workspaces npm (Node ≥ 22.18, TypeScript ejecutado directamente por Node):

| Paquete | Qué hace |
|---|---|
| `packages/mcp-server` (`pupusphone-mcp`) | Servidor MCP stdio que controla el móvil a través de la device API. |
| `packages/bridge` | WebSocket de voz (`:8766`) y proceso persistente de Claude Code con el MCP. |
| `packages/fake-phone` | Simulador de la device API para tests y desarrollo. |

Contrato: [`../docs/protocol.md`](../docs/protocol.md).

## Comandos

```bash
npm install
npm run typecheck
npm test                 # usa fake-phone, no necesita el móvil
npm run smoke            # bridge + fake-phone + claude real (gasta un poco de la suscripción)
npm run fake-phone       # simulador en :8765
npm run bridge           # requiere las variables de .env.example exportadas
```

Los programas leen `process.env`; no cargan `.env` por sí mismos. Variables en
[`.env.example`](.env.example).

## Conexión con el móvil

- **USB (desarrollo):** la app escucha en `127.0.0.1:8765` del móvil.
  `adb forward tcp:8765 tcp:8765` expone la device API en el PC
  (`PUPUS_PHONE_URL=http://127.0.0.1:8765`) y `adb reverse tcp:8766 tcp:8766`
  deja al móvil llegar al bridge en `ws://127.0.0.1:8766/voice`.
- **Tailscale (coche):** `PUPUS_PHONE_URL=http://<ip-tailscale-del-móvil>:8765`
  y, en la app, `ws://<ip-tailscale-del-pc>:8766/voice`.

## Usar el MCP desde Claude Code del PC

```bash
claude mcp add pupusphone --scope user \
  -e PUPUS_PHONE_URL=http://127.0.0.1:8765 -e PUPUS_PHONE_TOKEN=<token> \
  -- node <ruta>/pc/packages/mcp-server/src/cli.ts
```

## Aislamiento del bridge

El proceso de Claude Code del bridge no hereda la configuración del usuario:
`--setting-sources ""` (sin hooks, plugins ni CLAUDE.md), `--strict-mcp-config`
(solo el MCP del móvil), `--tools ""` (sin herramientas integradas),
`--permission-mode dontAsk`, `--disable-slash-commands`, prompt de sistema
propio (`packages/bridge/prompts/voice-system-prompt.md`), directorio de
trabajo vacío y entorno filtrado (`claude-args.ts`).

## Memoria

El asistente recuerda datos entre conversaciones con dos ficheros fuera del repo
(el repo es público; no metas datos personales en él):

- `~/.pupusphone/profile.md`: perfil del usuario escrito a mano (texto libre,
  máximo 8000 caracteres). Solo lectura para el asistente.
- `~/.pupusphone/memory.json`: datos que el asistente guarda por voz con las
  herramientas `remember` y `forget` del MCP (máximo 100, de hasta 300 caracteres;
  con la memoria llena, pide olvidar algo en vez de borrar lo antiguo).

El bridge los lee en cada arranque de Claude y los añade al prompt de sistema, así
que un dato guardado entra en el prompt en el siguiente arranque (`reset`, cierre
del modo coche o reinicio tras un fallo); dentro de la misma conversación ya lo
conoce. Un `memory.json` corrupto se omite con un aviso en el log y nunca se
sobrescribe. Rutas: `PUPUS_MEMORY_FILE` y `PUPUS_PROFILE_FILE`; vacías, desactivan
cada parte. En Termux, copia el perfil con
`adb push profile.md /sdcard/Download/` y, en Termux (con `termux-setup-storage`),
`cp ~/storage/downloads/profile.md ~/.pupusphone/`.
