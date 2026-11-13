/** Short, non-spoken Spanish status lines derived from the tool Claude is using. */

const KNOWN_APPS: Readonly<Record<string, string>> = {
  'com.spotify.music': 'Spotify',
  'com.whatsapp': 'WhatsApp',
  'com.google.android.dialer': 'el teléfono',
  'com.google.android.apps.maps': 'Maps',
  'com.waze': 'Waze',
  'com.google.android.youtube': 'YouTube',
  'com.google.android.apps.messaging': 'Mensajes',
  'org.telegram.messenger': 'Telegram',
  'com.android.chrome': 'Chrome',
};

const MEDIA: Readonly<Record<string, string>> = {
  play_pause: 'Play o pausa…',
  play: 'Reproduciendo…',
  pause: 'Pausando…',
  next: 'Siguiente canción…',
  previous: 'Canción anterior…',
  volume_up: 'Subiendo el volumen…',
  volume_down: 'Bajando el volumen…',
};

const PRESS: Readonly<Record<string, string>> = {
  back: 'Volviendo atrás…',
  home: 'Yendo al inicio…',
  recents: 'Abriendo recientes…',
  notifications: 'Mirando las notificaciones…',
  quick_settings: 'Abriendo ajustes rápidos…',
  lock_screen: 'Bloqueando la pantalla…',
};

const SIMPLE: Readonly<Record<string, string>> = {
  read_screen: 'Mirando la pantalla…',
  screenshot: 'Haciendo una captura…',
  tap: 'Pulsando…',
  long_press: 'Manteniendo pulsado…',
  swipe: 'Deslizando…',
  scroll: 'Desplazando…',
  type_text: 'Escribiendo…',
  list_apps: 'Buscando la app…',
  wait: 'Esperando…',
  play_music: 'Poniendo música…',
  now_playing: 'Mirando qué suena…',
  read_notifications: 'Leyendo las notificaciones…',
  find_contact: 'Buscando el contacto…',
  call: 'Llamando…',
  whatsapp_send: 'Enviando el WhatsApp…',
  navigate: 'Abriendo la navegación…',
  remember: 'Apuntándolo…',
  forget: 'Olvidándolo…',
};

function appName(pkg: unknown, name: unknown): string {
  if (typeof name === 'string' && name.trim() !== '') return name.trim();
  if (typeof pkg !== 'string') return 'la app';
  return KNOWN_APPS[pkg] ?? pkg.split('.').pop() ?? 'la app';
}

function urlStatus(url: unknown): string {
  if (typeof url !== 'string') return 'Abriendo enlace…';
  const lower = url.toLowerCase();
  if (lower.startsWith('spotify:search:')) return 'Buscando en Spotify…';
  if (lower.startsWith('spotify:')) return 'Abriendo Spotify…';
  if (lower.startsWith('tel:')) return 'Preparando la llamada…';
  if (lower.startsWith('whatsapp:') || lower.includes('wa.me/')) return 'Abriendo WhatsApp…';
  if (lower.startsWith('google.navigation:') || lower.startsWith('geo:')) return 'Abriendo la navegación…';
  return 'Abriendo enlace…';
}

/** Strips the MCP prefix: `mcp__phone__launch_app` → `launch_app`. */
export function shortToolName(name: string): string {
  const match = /^mcp__[^_]+(?:_[^_]+)*__(.+)$/.exec(name);
  return match?.[1] ?? name;
}

export function toolStatus(name: string, input: Readonly<Record<string, unknown>>): string {
  const tool = shortToolName(name);
  switch (tool) {
    case 'launch_app':
      return `Abriendo ${appName(input['package'], input['name'])}…`;
    case 'open_url':
      return urlStatus(input['url']);
    case 'media':
      return MEDIA[String(input['action'])] ?? 'Controlando la música…';
    case 'press':
      return PRESS[String(input['action'])] ?? 'Pulsando…';
    default:
      return SIMPLE[tool] ?? 'Trabajando…';
  }
}
