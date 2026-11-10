import { describe, expect, it } from 'vitest';
import { defaultScenario, flattenScreen } from '@pupusphone/fake-phone';
import { renderScreen } from '../src/render.ts';
import type { ScreenTree } from '../src/types.ts';

function launcherTree(): ScreenTree {
  const scenario = defaultScenario();
  const launcher = scenario.screens['launcher'];
  if (!launcher) throw new Error('missing launcher');
  return flattenScreen(launcher, scenario.size).tree;
}

describe('renderScreen', () => {
  it('renders a header and one line per meaningful node with centre and flags', () => {
    const out = renderScreen(launcherTree());
    const lines = out.split('\n');
    expect(lines[0]).toBe('app=com.google.android.apps.nexuslauncher screen=1080x2400 nodes=7');
    expect(out).toMatch(/^ {2}\[4\] TextView "Spotify" @165,1920 click$/m);
  });

  it('hides pure layout containers and does not indent for them', () => {
    const lines = renderScreen(launcherTree()).split('\n').slice(1);
    expect(lines.some((l) => l.includes('[0]'))).toBe(false);
    expect(lines[0]).toMatch(/^\[1\] FrameLayout #status_bar/);
    expect(lines[1]).toMatch(/^ {2}\[2\] TextView "12:30"/);
    expect(lines.find((l) => l.includes('"WhatsApp"'))).toMatch(/^ {2}\[/);
  });

  it('omits desc when it equals text and shows it otherwise', () => {
    const tree: ScreenTree = {
      package: 'x.y',
      screen: { width: 100, height: 100 },
      nodes: [
        { id: 0, parent: -1, cls: 'Button', text: 'Ok', desc: 'Ok', resId: null, bounds: [0, 0, 10, 10], flags: ['clickable'] },
        { id: 1, parent: -1, cls: 'ImageView', text: null, desc: 'Foto de "perfil"', resId: 'avatar', bounds: [0, 0, 20, 20], flags: [] },
      ],
    };
    const out = renderScreen(tree);
    expect(out).toContain('[0] Button "Ok" @5,5 click');
    expect(out).toContain('[1] ImageView desc:"Foto de \\"perfil\\"" #avatar @10,10');
  });

  it('truncates long text, collapses whitespace and caps node count', () => {
    const nodes = Array.from({ length: 5 }, (_, i) => ({
      id: i, parent: -1, cls: 'TextView', text: `línea\n${'x'.repeat(200)}`, desc: null, resId: null,
      bounds: [0, 0, 10, 10] as const, flags: [] as string[],
    }));
    const out = renderScreen({ package: null, screen: { width: 10, height: 10 }, nodes }, { maxNodes: 3, maxText: 20 });
    expect(out.split('\n')[1]).toBe('[0] TextView "línea xxxxxxxxxxxxx…" @5,5');
    expect(out).toContain('… 2 more nodes omitted');
    expect(out.startsWith('app=unknown')).toBe(true);
  });

  it('suggests a screenshot when nothing is readable', () => {
    const out = renderScreen({ package: 'g', screen: { width: 1, height: 1 }, nodes: [] });
    expect(out).toContain('try screenshot');
  });
});
