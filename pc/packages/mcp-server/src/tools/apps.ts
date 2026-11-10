import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { AppInfo } from '../types.ts';
import { act, failure, guarded, observeParam, text, type ToolContext } from './context.ts';

const LIST_APPS = 'List launchable apps as "label = package" lines. Only needed when the package name is unknown.';

const LAUNCH_APP = `Open an app by package (e.g. com.spotify.music) or by its visible name (e.g. "Spotify", matched
case- and accent-insensitively). If the goal is a specific content (song, chat, call, route), prefer open_url
with a deep link: it skips UI navigation. Returns the updated screen.`;

const OPEN_URL = `Open a URL or deep link with an Android intent (ACTION_VIEW by default). Much faster and more
reliable than navigating the UI; prefer it whenever a link exists. Examples:
- Spotify search: spotify:search:<query>; track/playlist: spotify:track:<id>
- WhatsApp chat with text prefilled (user must still confirm sending): https://wa.me/<intl number>?text=<urlencoded>
- Phone call screen: tel:<number> (dial only; ask the user before calling)
- Navigation: google.navigation:q=<urlencoded destination>; map search: geo:0,0?q=<query>
- Web: https://...
Optional "package" forces the handler app; "action" overrides the intent action; "extras" are string extras.`;

const MEDIA = `Send a media key or change music volume without touching the UI: play_pause, play, pause, next,
previous, volume_up, volume_down. Works on whichever app is playing; always prefer this over tapping player buttons.`;

const MEDIA_ACTIONS = ['play_pause', 'play', 'pause', 'next', 'previous', 'volume_up', 'volume_down'] as const;
const PACKAGE_RE = /^[A-Za-z][\w]*(\.[A-Za-z][\w]*)+$/;
const URI_RE = /^[a-z][a-z0-9+.-]*:\S.*$/i;

/** Percent-encodes characters Claude often leaves raw in deep links (spotify:search:Don't Stop Me Now). */
export function normalizeUri(url: string): string {
  return url.trim().replace(/\s+/g, '%20').replace(/'/g, '%27');
}

const APPS_TTL_MS = 60_000;

function normalize(value: string): string {
  return value.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().trim();
}

/** Resolves a visible app name to a package: exact label, then prefix, then substring. */
export function matchApp(apps: readonly AppInfo[], name: string): AppInfo | AppInfo[] {
  const target = normalize(name);
  const tiers = [
    apps.filter((a) => normalize(a.label) === target),
    apps.filter((a) => normalize(a.label).startsWith(target)),
    apps.filter((a) => normalize(a.label).includes(target)),
  ];
  for (const tier of tiers) {
    if (tier.length === 1 && tier[0]) return tier[0];
    if (tier.length > 1) return tier;
  }
  return [];
}

function appsCache(ctx: ToolContext): () => Promise<readonly AppInfo[]> {
  let cached: { at: number; apps: readonly AppInfo[] } | null = null;
  return async () => {
    if (cached && Date.now() - cached.at < APPS_TTL_MS) return cached.apps;
    const { apps } = await ctx.client.request<{ apps: AppInfo[] }>('GET', '/apps');
    cached = { at: Date.now(), apps };
    return apps;
  };
}

function registerLaunch(server: McpServer, ctx: ToolContext, getApps: () => Promise<readonly AppInfo[]>): void {
  server.registerTool(
    'launch_app',
    {
      title: 'Launch app',
      description: LAUNCH_APP,
      inputSchema: {
        package: z.string().regex(PACKAGE_RE).max(200).optional(),
        name: z.string().min(1).max(100).optional().describe('Visible app name, used when the package is unknown.'),
        observe: observeParam,
      },
    },
    guarded('launch_app', async (args) => {
      let pkg = args.package;
      if (!pkg && args.name) {
        const match = matchApp(await getApps(), args.name);
        if (Array.isArray(match)) {
          const options = match.map((a) => `${a.label} = ${a.package}`).join('; ');
          return failure(match.length === 0 ? `No app named "${args.name}". Use list_apps.` : `Ambiguous name, candidates: ${options}`);
        }
        pkg = match.package;
      }
      if (!pkg) return failure('Provide package or name.');
      const target = pkg;
      return act(ctx, `launched ${target}`, () => ctx.client.post('/apps/launch', { package: target }), args.observe);
    }),
  );
}

export function registerAppTools(server: McpServer, ctx: ToolContext): void {
  const getApps = appsCache(ctx);

  server.registerTool(
    'list_apps',
    { title: 'List apps', description: LIST_APPS, inputSchema: {}, annotations: { readOnlyHint: true } },
    guarded('list_apps', async () => text((await getApps()).map((a) => `${a.label} = ${a.package}`).join('\n') || '(no apps)')),
  );

  registerLaunch(server, ctx, getApps);

  server.registerTool(
    'open_url',
    {
      title: 'Open URL / deep link',
      description: OPEN_URL,
      inputSchema: {
        url: z.string().max(4000).regex(URI_RE, 'must be an absolute URI such as spotify:search:queen or https://...'),
        package: z.string().regex(PACKAGE_RE).max(200).optional(),
        action: z.string().regex(/^[\w.]+$/).max(200).optional(),
        extras: z.record(z.string().max(100), z.string().max(4000)).optional(),
        observe: observeParam,
      },
    },
    guarded('open_url', async ({ url: rawUrl, package: pkg, action, extras, observe }) => {
      const url = normalizeUri(rawUrl);
      if (/^(javascript|file|content):/i.test(url)) return failure('This URI scheme is not allowed.');
      const body = { data: url, package: pkg, action, extras };
      return act(ctx, `opened ${url}`, () => ctx.client.post('/intent', body), observe);
    }),
  );

  server.registerTool(
    'media',
    {
      title: 'Media keys',
      description: MEDIA,
      inputSchema: {
        action: z.enum(MEDIA_ACTIONS),
        observe: z.boolean().optional().describe('Default false here: media keys rarely change the screen.'),
      },
    },
    guarded('media', async ({ action, observe }) =>
      act(ctx, `media ${action}`, () => ctx.client.post('/media', { action }), observe ?? false)),
  );
}
