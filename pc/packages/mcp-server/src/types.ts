/** Device API shapes consumed by the MCP server (docs/protocol.md section 1). */

export interface TreeNode {
  readonly id: number;
  readonly parent: number;
  readonly cls: string;
  readonly text: string | null;
  readonly desc: string | null;
  readonly resId: string | null;
  readonly bounds: readonly [number, number, number, number];
  readonly flags: readonly string[];
}

export interface ScreenTree {
  readonly package: string | null;
  readonly screen: { readonly width: number; readonly height: number };
  readonly nodes: readonly TreeNode[];
}

export interface Screenshot {
  readonly mime: string;
  readonly base64: string;
  readonly width: number;
  readonly height: number;
}

export interface AppInfo {
  readonly package: string;
  readonly label: string;
}

export interface MediaSession {
  readonly package: string;
  readonly playing: boolean;
  readonly title: string | null;
  readonly artist: string | null;
  readonly album: string | null;
}

export interface MediaStateResponse {
  readonly sessions: readonly MediaSession[];
}

export interface PlaySearchResponse {
  readonly ok: boolean;
  readonly package: string | null;
  readonly via: 'session' | 'intent' | null;
}

export interface PhoneNotification {
  readonly key: string;
  readonly package: string;
  readonly app: string;
  readonly title: string | null;
  readonly text: string | null;
  /** Epoch milliseconds. */
  readonly postedAt: number;
}

export interface ContactNumber {
  readonly number: string;
  readonly label: string | null;
}

export interface Contact {
  readonly name: string;
  readonly numbers: readonly ContactNumber[];
}

export interface CallResponse {
  readonly ok: boolean;
  readonly mode: 'call' | 'dial';
}
