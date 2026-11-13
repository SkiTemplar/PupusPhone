/** Shapes of the PupusPhone Device API (docs/protocol.md, section 1). */

export type NodeFlag = 'clickable' | 'scrollable' | 'editable' | 'focused' | 'checked' | 'selected';

export interface TreeNode {
  readonly id: number;
  readonly parent: number;
  readonly cls: string;
  readonly text: string | null;
  readonly desc: string | null;
  readonly resId: string | null;
  readonly bounds: readonly [number, number, number, number];
  readonly flags: readonly NodeFlag[];
}

export interface ScreenSize {
  readonly width: number;
  readonly height: number;
}

export interface ScreenTree {
  readonly package: string | null;
  readonly screen: ScreenSize;
  readonly nodes: readonly TreeNode[];
}

/** Effect applied when a scripted node is tapped. */
export type TapEffect =
  | { readonly launch: string }
  | { readonly screen: string }
  /** Starts playback in the media session of `package` (e.g. tapping a Spotify search result). */
  | { readonly play: { readonly package: string; readonly title: string; readonly artist: string } };

/** Declarative node used to script fake screens; ids are assigned in preorder. */
export interface NodeDef {
  readonly cls: string;
  readonly text?: string;
  readonly desc?: string;
  readonly resId?: string;
  readonly bounds: readonly [number, number, number, number];
  readonly flags?: readonly NodeFlag[];
  readonly onTap?: TapEffect;
  readonly children?: readonly NodeDef[];
}

export interface ScreenDef {
  readonly package: string;
  readonly root: NodeDef;
}

export interface AppDef {
  readonly package: string;
  readonly label: string;
  /** Screen id shown when the app is launched. */
  readonly screen: string;
}

export interface MediaSessionDef {
  readonly package: string;
  readonly playing: boolean;
  readonly title: string | null;
  readonly artist: string | null;
  readonly album: string | null;
  /** The session announces ACTION_PLAY_FROM_SEARCH. */
  readonly supportsSearch?: boolean;
}

export interface NotificationDef {
  readonly key: string;
  readonly package: string;
  readonly app: string;
  readonly title: string | null;
  readonly text: string | null;
  /** Age at request time; `postedAt` is derived from it so "N min ago" stays deterministic. */
  readonly minutesAgo: number;
}

export interface ContactNumberDef {
  readonly number: string;
  readonly label: string | null;
}

export interface ContactDef {
  readonly name: string;
  readonly numbers: readonly ContactNumberDef[];
}

export interface Scenario {
  readonly size: ScreenSize;
  readonly homeScreen: string;
  readonly screens: Readonly<Record<string, ScreenDef>>;
  readonly apps: readonly AppDef[];
  /** URI scheme or prefix → package that handles it (e.g. "spotify:" → com.spotify.music). */
  readonly uriHandlers: Readonly<Record<string, string>>;
  /** URI prefix → screen id opened directly by an intent (e.g. a wa.me link opens the chat screen). */
  readonly deepLinks?: Readonly<Record<string, string>>;
  /** Active media sessions in system priority order. */
  readonly mediaSessions?: readonly MediaSessionDef[];
  readonly notifications?: readonly NotificationDef[];
  readonly contacts?: readonly ContactDef[];
}

/** One request received by the fake phone, for test assertions. */
export interface RecordedAction {
  readonly method: string;
  readonly path: string;
  readonly body: unknown;
  readonly query: Readonly<Record<string, string>>;
  readonly at: number;
}

export interface ApiResult {
  readonly status: number;
  readonly body: unknown;
}
