import { centerOf, clickableAncestor, flattenScreen, hitTest, type FlatScreen } from './tree.ts';
import type { ApiResult, RecordedAction, Scenario, TapEffect, TreeNode } from './types.ts';
import {
  BadRequest, asBody, int, oneOf, optBool, optInt, optStr, optStringMap, rangeNumber, str, type Body,
} from './validate.ts';

/** Smallest valid baseline JPEG (1x1), returned by /screen/shot. */
const TINY_JPEG =
  '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=';

const GLOBAL_ACTIONS = ['back', 'home', 'recents', 'notifications', 'quick_settings', 'lock_screen'] as const;
const MEDIA_ACTIONS = ['play_pause', 'play', 'pause', 'next', 'previous', 'volume_up', 'volume_down'] as const;
const DIRECTIONS = ['up', 'down', 'left', 'right'] as const;

export interface ApiRequest {
  readonly method: string;
  readonly path: string;
  readonly query: Readonly<Record<string, string>>;
  readonly body: unknown;
}

export interface MediaState {
  readonly playing: boolean;
  readonly volume: number;
  readonly track: number;
}

type Handler = (body: Body, query: Readonly<Record<string, string>>) => ApiResult;

const ok = (value = true): ApiResult => ({ status: 200, body: { ok: value } });

/**
 * In-memory phone: scripted screens, navigation stack, typed text and media state.
 * Every request is recorded in `actions` so tests can assert what the client did.
 */
export class FakePhone {
  readonly scenario: Scenario;
  private stack: string[];
  private texts = new Map<string, Map<string, string>>();
  private snapshot: FlatScreen | null = null;
  private recorded: RecordedAction[] = [];
  private mediaState: MediaState = { playing: false, volume: 7, track: 0 };
  accessibility = true;
  private readonly routes: Readonly<Record<string, Handler>>;

  constructor(scenario: Scenario) {
    this.scenario = scenario;
    this.stack = [scenario.homeScreen];
    this.routes = {
      'GET /health': () => this.health(),
      'GET /screen/tree': () => this.tree(),
      'GET /screen/shot': (_b, q) => this.shot(q),
      'POST /input/tap': (b) => this.tap(b),
      'POST /input/long_press': (b) => this.gesture(b, ['x', 'y']),
      'POST /input/swipe': (b) => this.gesture(b, ['x1', 'y1', 'x2', 'y2']),
      'POST /input/scroll': (b) => this.scroll(b),
      'POST /input/text': (b) => this.text(b),
      'POST /input/global': (b) => this.global(b),
      'GET /apps': () => this.apps(),
      'POST /apps/launch': (b) => this.launch(str(b, 'package')),
      'POST /intent': (b) => this.intent(b),
      'POST /media': (b) => this.media(b),
    };
  }

  get actions(): readonly RecordedAction[] {
    return [...this.recorded];
  }

  get currentScreen(): string {
    return this.stack[this.stack.length - 1] ?? this.scenario.homeScreen;
  }

  get mediaStatus(): MediaState {
    return { ...this.mediaState };
  }

  /** Requests received for a given route, e.g. `requests('GET', '/screen/tree')`. */
  requests(method: string, path: string): readonly RecordedAction[] {
    return this.recorded.filter((a) => a.method === method && a.path === path);
  }

  goTo(screen: string): void {
    if (!this.scenario.screens[screen]) throw new Error(`unknown screen ${screen}`);
    this.stack = [...this.stack, screen];
  }

  clearActions(): void {
    this.recorded = [];
  }

  handle(req: ApiRequest): ApiResult {
    this.recorded = [...this.recorded, { ...req, at: Date.now() }];
    const handler = this.routes[`${req.method} ${req.path}`];
    if (!handler) return { status: 404, body: { error: 'not_found' } };
    try {
      return handler(asBody(req.body), req.query);
    } catch (err) {
      if (err instanceof BadRequest) return { status: 400, body: { error: err.message } };
      return { status: 500, body: { error: err instanceof Error ? err.message : 'internal_error' } };
    }
  }

  private flat(): FlatScreen {
    const id = this.currentScreen;
    const def = this.scenario.screens[id];
    if (!def) throw new Error(`unknown screen ${id}`);
    return flattenScreen(def, this.scenario.size, this.texts.get(id));
  }

  private health(): ApiResult {
    return {
      status: 200,
      body: {
        ok: true,
        version: '0.1.0',
        accessibility: this.accessibility,
        screen: this.scenario.size,
        foregroundPackage: this.flat().tree.package,
      },
    };
  }

  private tree(): ApiResult {
    this.snapshot = this.flat();
    return { status: 200, body: this.snapshot.tree };
  }

  private shot(query: Readonly<Record<string, string>>): ApiResult {
    const scale = rangeNumber(query['scale'], 0.5, 0.1, 1, 'scale');
    rangeNumber(query['quality'], 60, 10, 100, 'quality');
    const { width, height } = this.scenario.size;
    return {
      status: 200,
      body: { mime: 'image/jpeg', base64: TINY_JPEG, width: Math.round(width * scale), height: Math.round(height * scale) },
    };
  }

  private resolveNode(nodeId: number): TreeNode | undefined {
    return this.snapshot?.tree.nodes[nodeId];
  }

  private tap(body: Body): ApiResult {
    const nodeId = optInt(body, 'nodeId');
    if (nodeId !== undefined) {
      const node = this.resolveNode(nodeId);
      if (!node || !this.snapshot) return { status: 404, body: { error: 'node_not_found' } };
      const target = clickableAncestor(this.snapshot.tree.nodes, node.id) ?? node;
      this.applyEffect(this.snapshot.effects.get(target.id));
      return ok();
    }
    const x = int(body, 'x');
    const y = int(body, 'y');
    const flat = this.flat();
    const hit = hitTest(flat.tree.nodes, x, y);
    if (hit) this.applyEffect(flat.effects.get(hit.id));
    return ok();
  }

  private applyEffect(effect: TapEffect | undefined): void {
    if (!effect) return;
    if ('launch' in effect) this.launch(effect.launch);
    else this.goTo(effect.screen);
  }

  private gesture(body: Body, keys: readonly string[]): ApiResult {
    for (const key of keys) int(body, key);
    const duration = optInt(body, 'durationMs');
    if (duration !== undefined && (duration < 1 || duration > 10000)) throw new BadRequest('durationMs out of range');
    return ok();
  }

  private scroll(body: Body): ApiResult {
    oneOf(body, 'direction', DIRECTIONS);
    const nodeId = optInt(body, 'nodeId');
    if (nodeId !== undefined) {
      const node = this.resolveNode(nodeId);
      if (!node) return { status: 404, body: { error: 'node_not_found' } };
      return ok(node.flags.includes('scrollable'));
    }
    return ok(this.flat().tree.nodes.some((n) => n.flags.includes('scrollable')));
  }

  private text(body: Body): ApiResult {
    const text = str(body, 'text');
    optBool(body, 'submit');
    const nodeId = optInt(body, 'nodeId');
    const nodes = nodeId === undefined ? this.flat().tree.nodes : this.snapshot?.tree.nodes ?? [];
    const target = nodeId === undefined ? nodes.find((n) => n.flags.includes('focused') && n.flags.includes('editable')) : nodes[nodeId];
    if (nodeId !== undefined && !target) return { status: 404, body: { error: 'node_not_found' } };
    if (!target || !target.flags.includes('editable') || target.resId === null) return ok(false);
    const screenTexts = new Map(this.texts.get(this.currentScreen));
    screenTexts.set(target.resId, text);
    this.texts = new Map(this.texts).set(this.currentScreen, screenTexts);
    return ok();
  }

  private global(body: Body): ApiResult {
    const action = oneOf(body, 'action', GLOBAL_ACTIONS);
    if (action === 'home') this.stack = [this.scenario.homeScreen];
    if (action === 'back' && this.stack.length > 1) this.stack = this.stack.slice(0, -1);
    return ok();
  }

  private apps(): ApiResult {
    const apps = this.scenario.apps.map((a) => ({ package: a.package, label: a.label }));
    return { status: 200, body: { apps } };
  }

  private launch(pkg: string): ApiResult {
    const app = this.scenario.apps.find((a) => a.package === pkg);
    if (!app) return ok(false);
    this.stack = [...this.stack, app.screen];
    return ok();
  }

  private intent(body: Body): ApiResult {
    optStr(body, 'action');
    optStringMap(body, 'extras');
    const data = optStr(body, 'data');
    const pkg = optStr(body, 'package');
    const handlers = Object.entries(this.scenario.uriHandlers);
    const handler = pkg ?? handlers.find(([prefix]) => data?.startsWith(prefix))?.[1];
    if (!handler) return ok(false);
    return this.launch(handler);
  }

  private media(body: Body): ApiResult {
    const action = oneOf(body, 'action', MEDIA_ACTIONS);
    const s = this.mediaState;
    const next: Record<(typeof MEDIA_ACTIONS)[number], MediaState> = {
      play_pause: { ...s, playing: !s.playing },
      play: { ...s, playing: true },
      pause: { ...s, playing: false },
      next: { ...s, track: s.track + 1 },
      previous: { ...s, track: Math.max(0, s.track - 1) },
      volume_up: { ...s, volume: Math.min(15, s.volume + 1) },
      volume_down: { ...s, volume: Math.max(0, s.volume - 1) },
    };
    this.mediaState = next[action];
    return ok();
  }
}

export { centerOf };
