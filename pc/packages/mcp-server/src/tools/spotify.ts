import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { normalize, textSignature } from '../text-match.ts';
import type { MediaStateResponse, ScreenTree, TreeNode } from '../types.ts';
import { failure, sleep, text, type ToolContext } from './context.ts';

/*
 * Spotify ignores play-from-search requests (media session and intent) from apps it does not trust,
 * so play_music drives its UI instead: open the search deep link, wait for the results of this query,
 * tap the top result and confirm the media session is playing. Verified on the real app 2026-11-13.
 */

export const SPOTIFY = 'com.spotify.music';
const RESULTS_POLLS = 12;
const PLAYING_POLLS = 8;
const MAX_POLL_MS = 400;

function pollMs(ctx: ToolContext): number {
  return Math.min(MAX_POLL_MS, Math.max(1, ctx.config.settleMs));
}

/** The search box once it shows this query (the deep link fills it before the results load). */
function searchBox(tree: ScreenTree, query: string): TreeNode | undefined {
  const wanted = normalize(query);
  return tree.nodes.find((n) => n.flags.includes('editable') && normalize(n.text ?? '') === wanted);
}

/** The first result row: the topmost "title" node below the search box. */
export function topResult(tree: ScreenTree, box: TreeNode): TreeNode | undefined {
  return tree.nodes
    .filter((n) => n.resId === 'title' && n.text && n.bounds[1] >= box.bounds[3])
    .sort((a, b) => a.bounds[1] - b.bounds[1])[0];
}

/** Waits until Spotify shows this query with a top result and the screen stopped changing. */
async function waitForResults(ctx: ToolContext, query: string): Promise<{ tree: ScreenTree; node: TreeNode } | string> {
  let previous = '';
  let last: ScreenTree | undefined;
  for (let i = 0; i < RESULTS_POLLS; i++) {
    await sleep(pollMs(ctx));
    const tree = await ctx.client.tree(true);
    ctx.lastTree = tree;
    last = tree;
    if (tree.package !== SPOTIFY) continue;
    const box = searchBox(tree, query);
    const node = box && topResult(tree, box);
    const signature = textSignature(tree);
    if (node && signature === previous) return { tree, node };
    previous = signature;
  }
  if (last?.package !== SPOTIFY) {
    return `Spotify did not come to the front (foreground: ${last?.package ?? 'unknown'}). The phone is probably locked: tell the user to unlock it to choose a song; media play can still resume the last one.`;
  }
  return 'Spotify opened the search but no result appeared. Try a shorter query or ask the user.';
}

async function waitForPlaying(ctx: ToolContext): Promise<string | undefined> {
  for (let i = 0; i < PLAYING_POLLS; i++) {
    await sleep(pollMs(ctx));
    const { sessions } = await ctx.client.request<MediaStateResponse>('GET', '/media/state');
    const playing = sessions.find((s) => s.package === SPOTIFY && s.playing);
    if (playing) return playing.artist ? `${playing.title ?? ''} — ${playing.artist}` : (playing.title ?? 'unknown title');
  }
  return undefined;
}

/** Plays the top Spotify search result for `query` through the UI. */
export async function playOnSpotify(ctx: ToolContext, query: string): Promise<CallToolResult> {
  const data = `spotify:search:${encodeURIComponent(query)}`;
  const opened = await ctx.client.request<{ ok: boolean }>('POST', '/intent', { data, package: SPOTIFY });
  if (!opened.ok) return failure('Spotify could not be opened (is it installed?).');
  const results = await waitForResults(ctx, query);
  if (typeof results === 'string') return failure(results);
  await ctx.client.request('POST', '/input/tap', { nodeId: results.node.id });
  const playing = await waitForPlaying(ctx);
  if (playing) return text(`playing: ${playing} (${SPOTIFY})`);
  return failure(`Tapped the top result "${results.node.text ?? ''}" but Spotify is not playing. Tell the user in one sentence.`);
}
