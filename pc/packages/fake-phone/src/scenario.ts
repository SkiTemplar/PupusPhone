import type { NodeDef, Scenario } from './types.ts';

const W = 1080;
const H = 2400;

function statusBar(): NodeDef {
  return {
    cls: 'FrameLayout',
    resId: 'status_bar',
    bounds: [0, 0, W, 80],
    children: [{ cls: 'TextView', text: '12:30', resId: 'clock', bounds: [40, 10, 200, 70] }],
  };
}

function appIcon(label: string, pkg: string, col: number, row: number): NodeDef {
  const left = 60 + col * 250;
  const top = 1800 + row * 280;
  return {
    cls: 'TextView',
    text: label,
    desc: label,
    bounds: [left, top, left + 210, top + 240],
    flags: ['clickable'],
    onTap: { launch: pkg },
  };
}

const launcher: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    {
      cls: 'RecyclerView',
      resId: 'workspace',
      bounds: [0, 80, W, 2300],
      flags: ['scrollable'],
      children: [
        appIcon('Spotify', 'com.spotify.music', 0, 0),
        appIcon('WhatsApp', 'com.whatsapp', 1, 0),
        appIcon('Teléfono', 'com.google.android.dialer', 2, 0),
        appIcon('Maps', 'com.google.android.apps.maps', 3, 0),
      ],
    },
  ],
};

const spotifyHome: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    { cls: 'TextView', text: 'Buenas tardes', resId: 'title', bounds: [40, 120, 700, 200] },
    {
      cls: 'Button',
      desc: 'Buscar',
      resId: 'search_tab',
      bounds: [270, 2250, 540, 2400],
      flags: ['clickable'],
      onTap: { screen: 'spotify-search' },
    },
    {
      cls: 'ImageButton',
      desc: 'Reproducir',
      resId: 'play_pause_button',
      bounds: [900, 2100, 1040, 2220],
      flags: ['clickable'],
    },
  ],
};

const spotifySearch: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    {
      cls: 'EditText',
      text: '¿Qué te apetece escuchar?',
      resId: 'query',
      bounds: [40, 120, 1040, 240],
      flags: ['clickable', 'editable', 'focused'],
    },
    {
      cls: 'RecyclerView',
      resId: 'results',
      bounds: [0, 260, W, 2200],
      flags: ['scrollable'],
    },
  ],
};

/** Results of a spotify:search: deep link, laid out like the real app (top result row, resId "title"). */
const spotifyResults: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    { cls: 'ImageButton', desc: 'Cancel', bounds: [0, 120, 150, 240], flags: ['clickable'] },
    { cls: 'EditText', text: 'Bohemian Rhapsody Queen', bounds: [150, 120, 900, 240], flags: ['editable'] },
    { cls: 'TextView', text: 'Songs', bounds: [40, 280, 200, 340], flags: ['clickable'] },
    {
      cls: 'LinearLayout',
      bounds: [0, 400, W, 560],
      flags: ['clickable'],
      onTap: { play: { package: 'com.spotify.music', title: 'Bohemian Rhapsody', artist: 'Queen' } },
      children: [
        { cls: 'TextView', text: 'Bohemian Rhapsody', resId: 'title', bounds: [200, 410, 900, 470] },
        { cls: 'TextView', text: 'Song • Queen', resId: 'subtitle', bounds: [200, 480, 900, 540] },
      ],
    },
    { cls: 'TextView', text: 'Another One Bites The Dust', resId: 'title', bounds: [200, 900, 900, 960] },
  ],
};

/** Route preview opened by a maps/dir URL, laid out like the real app (no resource ids). */
const mapsRoute: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    { cls: 'TextView', text: 'Your location', bounds: [176, 218, 506, 293] },
    { cls: 'TextView', text: 'Puerta del Sol', bounds: [176, 375, 523, 450] },
    { cls: 'FrameLayout', desc: 'Swap start and destination', bounds: [900, 334, 1040, 490], flags: ['clickable'] },
    { cls: 'TextView', text: 'Crash on A-1', bounds: [40, 600, 700, 660] },
    { cls: 'Button', text: 'Start', bounds: [65, 2100, 347, 2250], flags: ['clickable'], onTap: { screen: 'maps' } },
  ],
};

const whatsappChats: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    { cls: 'TextView', text: 'WhatsApp', resId: 'toolbar_title', bounds: [40, 100, 500, 200] },
    {
      cls: 'ListView',
      resId: 'conversations',
      bounds: [0, 220, W, 2300],
      flags: ['scrollable'],
      children: [
        {
          cls: 'LinearLayout',
          resId: 'contact_row',
          bounds: [0, 220, W, 400],
          flags: ['clickable'],
          children: [
            { cls: 'TextView', text: 'Mamá', resId: 'conversation_contact_name', bounds: [200, 240, 700, 300] },
            { cls: 'TextView', text: '¿Llegas a cenar?', resId: 'single_msg_tv', bounds: [200, 310, 900, 380] },
          ],
        },
      ],
    },
  ],
};

const dialer: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    { cls: 'EditText', resId: 'digits', bounds: [40, 300, 1040, 450], flags: ['editable'] },
    { cls: 'ImageButton', desc: 'Llamar', resId: 'dialpad_floating_action_button', bounds: [440, 2100, 640, 2300], flags: ['clickable'] },
  ],
};

const maps: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    { cls: 'EditText', text: 'Busca aquí', resId: 'search_omnibox_text_box', bounds: [40, 120, 1040, 240], flags: ['clickable', 'editable'] },
  ],
};

/** Chat opened by a wa.me deep link: text field prefilled, send button described as "Enviar". */
const whatsappChat: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [
    statusBar(),
    { cls: 'TextView', text: 'Chat', resId: 'conversation_contact_name', bounds: [200, 100, 700, 200] },
    { cls: 'ImageButton', desc: 'Adjuntar', resId: 'input_attach_button', bounds: [700, 2200, 820, 2320], flags: ['clickable'] },
    { cls: 'EditText', resId: 'entry', bounds: [40, 2200, 690, 2320], flags: ['clickable', 'editable', 'focused'] },
    { cls: 'ImageButton', desc: 'Enviar', resId: 'send', bounds: [940, 2200, 1060, 2320], flags: ['clickable'] },
  ],
};

const youtubeMusic: NodeDef = {
  cls: 'FrameLayout',
  bounds: [0, 0, W, H],
  children: [statusBar(), { cls: 'TextView', text: 'Inicio', resId: 'title', bounds: [40, 120, 700, 200] }],
};

const MEDIA_SESSIONS: Scenario['mediaSessions'] = [
  { package: 'com.google.android.apps.podcasts', playing: true, title: 'Un tema al día', artist: 'elDiario.es', album: null },
  {
    package: 'com.spotify.music',
    playing: false,
    title: 'Bohemian Rhapsody',
    artist: 'Queen',
    album: 'A Night at the Opera',
    supportsSearch: true,
  },
];

const NOTIFICATIONS: Scenario['notifications'] = [
  { key: 'n1', package: 'com.whatsapp', app: 'WhatsApp', title: 'Mamá', text: '¿Llegas a cenar?', minutesAgo: 3 },
  { key: 'n2', package: 'com.google.android.gm', app: 'Gmail', title: 'Factura de la luz', text: 'Tu factura de noviembre ya está disponible', minutesAgo: 25 },
  { key: 'n3', package: 'com.whatsapp', app: 'WhatsApp', title: 'Fútbol', text: 'Javi: mañana a las 8\nLaura: vale', minutesAgo: 90 },
];

const CONTACTS: Scenario['contacts'] = [
  { name: 'Papá', numbers: [{ number: '+34 612 345 678', label: 'Móvil' }] },
  { name: 'Mamá', numbers: [{ number: '91 555 12 34', label: 'Casa' }, { number: '600 11 22 33', label: 'Móvil' }] },
  { name: 'Javi Pérez', numbers: [{ number: '+34 655 000 111', label: 'Mobile' }] },
  { name: 'Javier López', numbers: [{ number: '677 888 999', label: null }] },
];

/** Default scenario: launcher with four apps; Spotify has a home and a search screen. */
export function defaultScenario(): Scenario {
  return {
    size: { width: W, height: H },
    homeScreen: 'launcher',
    screens: {
      launcher: { package: 'com.google.android.apps.nexuslauncher', root: launcher },
      spotify: { package: 'com.spotify.music', root: spotifyHome },
      'spotify-search': { package: 'com.spotify.music', root: spotifySearch },
      'spotify-results': { package: 'com.spotify.music', root: spotifyResults },
      'maps-route': { package: 'com.google.android.apps.maps', root: mapsRoute },
      whatsapp: { package: 'com.whatsapp', root: whatsappChats },
      dialer: { package: 'com.google.android.dialer', root: dialer },
      maps: { package: 'com.google.android.apps.maps', root: maps },
      'whatsapp-chat': { package: 'com.whatsapp', root: whatsappChat },
      'youtube-music': { package: 'com.google.android.apps.youtube.music', root: youtubeMusic },
    },
    apps: [
      { package: 'com.spotify.music', label: 'Spotify', screen: 'spotify' },
      { package: 'com.whatsapp', label: 'WhatsApp', screen: 'whatsapp' },
      { package: 'com.google.android.dialer', label: 'Teléfono', screen: 'dialer' },
      { package: 'com.google.android.apps.maps', label: 'Maps', screen: 'maps' },
      { package: 'com.google.android.apps.youtube.music', label: 'YouTube Music', screen: 'youtube-music' },
    ],
    deepLinks: { 'https://wa.me/': 'whatsapp-chat', 'spotify:search:': 'spotify-results', 'https://www.google.com/maps/dir/': 'maps-route' },
    mediaSessions: MEDIA_SESSIONS,
    notifications: NOTIFICATIONS,
    contacts: CONTACTS,
    uriHandlers: {
      'spotify:': 'com.spotify.music',
      'whatsapp:': 'com.whatsapp',
      'https://wa.me/': 'com.whatsapp',
      'tel:': 'com.google.android.dialer',
      'geo:': 'com.google.android.apps.maps',
      'google.navigation:': 'com.google.android.apps.maps',
    },
  };
}
