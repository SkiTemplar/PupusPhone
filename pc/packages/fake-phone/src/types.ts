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
export type TapEffect = { readonly launch: string } | { readonly screen: string };

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

export interface Scenario {
  readonly size: ScreenSize;
  readonly homeScreen: string;
  readonly screens: Readonly<Record<string, ScreenDef>>;
  readonly apps: readonly AppDef[];
  /** URI scheme or prefix → package that handles it (e.g. "spotify:" → com.spotify.music). */
  readonly uriHandlers: Readonly<Record<string, string>>;
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
