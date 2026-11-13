import { readMemory, readProfile, renderMemoryPrompt, type MemoryEntry } from 'pupusphone-mcp/memory';
import type { BridgeConfig } from './config.ts';
import { log } from './log.ts';

type MemoryPaths = Pick<BridgeConfig, 'memoryFile' | 'profileFile'>;

function describe(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * Appends the user profile and the saved facts to the base voice prompt. A broken memory or profile
 * file must not take the assistant down: it is logged and that part is left out of the prompt.
 */
export function composeSystemPrompt(basePrompt: string, paths: MemoryPaths): string {
  if (!paths.memoryFile && !paths.profileFile) return basePrompt;
  let entries: readonly MemoryEntry[] | undefined;
  if (paths.memoryFile) {
    entries = [];
    try {
      entries = readMemory(paths.memoryFile);
    } catch (err) {
      log.warn(`memory left out of the prompt: ${describe(err)}`);
    }
  }
  let profile: string | undefined;
  if (paths.profileFile) {
    try {
      const read = readProfile(paths.profileFile);
      if (read?.truncated) log.warn(`profile ${paths.profileFile} is too long; only its beginning goes into the prompt`);
      profile = read?.text;
    } catch (err) {
      log.warn(`profile left out of the prompt: ${describe(err)}`);
    }
  }
  if (!paths.memoryFile && !profile) return basePrompt;
  log.debug(`prompt memory: ${entries?.length ?? 'disabled'} facts, profile ${profile ? `${profile.length} chars` : 'none'}`);
  return `${basePrompt}\n\n${renderMemoryPrompt(entries, profile)}`;
}
