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
