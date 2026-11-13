import type { ApiResult, ContactDef, MediaSessionDef, Scenario } from './types.ts';
import { BadRequest, optStr, str, type Body } from './validate.ts';

const MAX_NOTIFICATIONS = 30;
const MAX_CONTACTS = 10;
const CALL_NUMBER_RE = /^\+?[0-9 ()-]{3,20}$/;
const FOUND_ARTIST = 'Fake Artist';

const NO_NOTIFICATION_ACCESS: ApiResult = { status: 403, body: { error: 'notification_access_missing' } };
const NO_CONTACTS_PERMISSION: ApiResult = { status: 403, body: { error: 'contacts_permission_missing' } };

function normalize(value: string): string {
  return value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim();
}

function publicSession(s: MediaSessionDef): Omit<MediaSessionDef, 'supportsSearch'> {
  return { package: s.package, playing: s.playing, title: s.title, artist: s.artist, album: s.album };
}

/**
 * Media sessions, notifications, contacts and calls (the endpoints backed by Android system services).
 * The permission switches are public so tests can trigger the 403 cases.
 */
export class DeviceServices {
  notificationAccess = true;
  contactsPermission = true;
  callPermission = true;
  private sessions: readonly MediaSessionDef[];
  private readonly scenario: Scenario;
  private readonly launch: (pkg: string) => boolean;

  constructor(scenario: Scenario, launch: (pkg: string) => boolean) {
    this.scenario = scenario;
    this.launch = launch;
    this.sessions = scenario.mediaSessions ?? [];
  }

  get mediaSessions(): readonly MediaSessionDef[] {
    return [...this.sessions];
  }

  mediaState(): ApiResult {
    if (!this.notificationAccess) return NO_NOTIFICATION_ACCESS;
    return { status: 200, body: { sessions: this.sessions.map(publicSession) } };
  }

  /** Makes `pkg` the playing session (creating it if needed) and pauses the others. */
  startPlaying(pkg: string, title: string, artist: string): void {
    const current = this.sessions.find((s) => s.package === pkg);
    const started: MediaSessionDef = { album: null, ...current, package: pkg, playing: true, title, artist };
    const others = this.sessions.filter((s) => s.package !== pkg).map((s) => ({ ...s, playing: false }));
    this.sessions = [started, ...others];
  }

  /** Session path: the session starts playing the query. Intent path: the app is launched, nothing plays yet. */
  playSearch(body: Body): ApiResult {
    const query = str(body, 'query');
    if (query.trim() === '') throw new BadRequest('query must not be empty');
    const pkg = optStr(body, 'package');
    if (!this.notificationAccess) return NO_NOTIFICATION_ACCESS;
    const session = pkg === undefined
      ? this.sessions.find((s) => s.supportsSearch === true)
      : this.sessions.find((s) => s.package === pkg);
    if (!session) {
      if (pkg === undefined) return { status: 409, body: { error: 'no_media_session' } };
      const launched = this.launch(pkg);
      return { status: 200, body: { ok: launched, package: launched ? pkg : null, via: launched ? 'intent' : null } };
    }
    const started: MediaSessionDef = { ...session, playing: true, title: query, artist: FOUND_ARTIST, album: null };
    const others = this.sessions.filter((s) => s !== session).map((s) => ({ ...s, playing: false }));
    this.sessions = [started, ...others];
    return { status: 200, body: { ok: true, package: session.package, via: 'session' } };
  }

  notifications(query: Readonly<Record<string, string>>): ApiResult {
    if (!this.notificationAccess) return NO_NOTIFICATION_ACCESS;
    const pkg = query['package'];
    const now = Date.now();
    const notifications = (this.scenario.notifications ?? [])
      .filter((n) => pkg === undefined || n.package === pkg)
      .map((n) => ({ key: n.key, package: n.package, app: n.app, title: n.title, text: n.text, postedAt: now - n.minutesAgo * 60_000 }))
      .sort((a, b) => b.postedAt - a.postedAt)
      .slice(0, MAX_NOTIFICATIONS);
    return { status: 200, body: { notifications } };
  }

  contacts(query: Readonly<Record<string, string>>): ApiResult {
    const q = query['q'];
    if (q === undefined || q.length < 1 || q.length > 64) throw new BadRequest('q must have 1-64 characters');
    if (!this.contactsPermission) return NO_CONTACTS_PERMISSION;
    const needle = normalize(q);
    const contacts: ContactDef[] = (this.scenario.contacts ?? [])
      .filter((c) => normalize(c.name).includes(needle))
      .slice(0, MAX_CONTACTS)
      .map((c) => ({ name: c.name, numbers: c.numbers.map((n) => ({ ...n })) }));
    return { status: 200, body: { contacts } };
  }

  call(body: Body): ApiResult {
    const number = str(body, 'number');
    if (!CALL_NUMBER_RE.test(number)) throw new BadRequest('number must match ^\\+?[0-9 ()-]{3,20}$');
    return { status: 200, body: { ok: true, mode: this.callPermission ? 'call' : 'dial' } };
  }
}
