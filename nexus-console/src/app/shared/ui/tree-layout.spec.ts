import { describe, expect, it } from 'vitest';
import { layoutTree, MapNode, NODE_HEIGHT, ROW } from './tree-layout';

const node = (id: string, children: MapNode[] = []): MapNode => ({ id, icon: 'workflow', kind: 'k', title: id, signals: [], children });

describe('layoutTree', () => {
  const tree = node('root', [node('a', [node('a1'), node('a2')]), node('b')]);

  it('should give each visible leaf its own row and center parents on their children', () => {
    const placed = Object.fromEntries(layoutTree(tree, new Set()).nodes.map((p) => [p.node.id, p]));

    expect([placed['a1'].y, placed['a2'].y, placed['b'].y]).toEqual([0, ROW, 2 * ROW]);
    expect(placed['a'].y).toBe(ROW / 2);
    expect(placed['root'].y).toBe((ROW / 2 + 2 * ROW) / 2);
    expect(placed['a1'].x).toBeGreaterThan(placed['a'].x);
  });

  it('should list nodes top-down so keyboard focus follows reading order', () => {
    expect(layoutTree(tree, new Set()).nodes.map((p) => p.node.id)).toEqual(['root', 'a', 'a1', 'a2', 'b']);
  });

  it('should hide a collapsed subtree but keep its child count', () => {
    const layout = layoutTree(tree, new Set(['a']));
    const a = layout.nodes.find((p) => p.node.id === 'a');

    expect(layout.nodes.map((p) => p.node.id)).toEqual(['root', 'a', 'b']);
    expect(a).toMatchObject({ collapsed: true, childCount: 2, y: 0 });
    expect(layout.height).toBe(ROW + NODE_HEIGHT + 20);
  });

  it('should draw one arrow per visible child', () => {
    expect(layoutTree(tree, new Set()).arrows).toHaveLength(4);
    expect(layoutTree(tree, new Set(['root'])).arrows).toHaveLength(0);
  });
});
