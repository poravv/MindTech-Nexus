import { IconName } from './icon';
import { Tone } from './status-badge';

/** One status shown as an icon on a node; the label is its accessible name and tooltip. */
export interface MapSignal {
  icon: IconName;
  tone: Tone;
  label: string;
}

export interface MapAction {
  id: string;
  label: string;
  icon: IconName;
}

/** A node of the integration map: Nexus, a tenant, an integration, a connection or an operation. */
export interface MapNode {
  id: string;
  /** 'nexus' draws the brand mark (root of "Todos los tenants"). */
  icon: IconName | 'nexus';
  /** Type label under the icon ("integración", "POS", "SIFEN"…). */
  kind: string;
  title: string;
  link?: string | unknown[];
  signals: MapSignal[];
  /** Second line under the signals ("3 integraciones · 1 con fallas"). */
  note?: string;
  /** ISO date for the age pill (last activity or last change). */
  since?: string | null;
  actions?: MapAction[];
  children?: MapNode[];
}

export interface PlacedNode {
  node: MapNode;
  depth: number;
  x: number;
  y: number;
  childCount: number;
  collapsed: boolean;
}

export interface TreeLayout {
  /** Pre-order, so Tab walks the map top-down like reading it. */
  nodes: PlacedNode[];
  /** Dashed links that end in an arrow at a child, and the dashed stub that leaves a parent. */
  branches: string[];
  arrows: string[];
  /** Solid vertical spines that fan a parent out to its children. */
  trunks: string[];
  width: number;
  height: number;
}

export const NODE_WIDTH = 320;
export const NODE_HEIGHT = 112;
export const ROW = 144;
export const GAP_X = 72;
const ARROW_GAP = 4;

/**
 * Left-to-right tidy tree: each visible leaf takes the next row, a parent sits in the middle of its children.
 * Collapsed nodes keep their child count but hide the subtree.
 */
export function layoutTree(root: MapNode, collapsed: ReadonlySet<string>): TreeLayout {
  const layout: TreeLayout = { nodes: [], branches: [], arrows: [], trunks: [], width: 0, height: 0 };
  let row = 0;
  let maxDepth = 0;

  const visit = (node: MapNode, depth: number): PlacedNode => {
    const children = node.children ?? [];
    const isCollapsed = collapsed.has(node.id);
    const placed: PlacedNode = { node, depth, x: depth * (NODE_WIDTH + GAP_X), y: 0, childCount: children.length, collapsed: isCollapsed };
    layout.nodes.push(placed);
    maxDepth = Math.max(maxDepth, depth);

    const shown = isCollapsed ? [] : children.map((child) => visit(child, depth + 1));
    if (!shown.length) {
      placed.y = row++ * ROW;
      return placed;
    }
    placed.y = (shown[0].y + shown[shown.length - 1].y) / 2;

    const fromX = placed.x + NODE_WIDTH;
    const midY = placed.y + NODE_HEIGHT / 2;
    const trunkX = fromX + GAP_X / 2;
    const ys = shown.map((child) => child.y + NODE_HEIGHT / 2);
    layout.branches.push(`M${fromX} ${midY}H${trunkX}`);
    layout.trunks.push(`M${trunkX} ${Math.min(midY, ys[0])}V${Math.max(midY, ys[ys.length - 1])}`);
    for (const [index, child] of shown.entries()) {
      layout.arrows.push(`M${trunkX} ${ys[index]}H${child.x - ARROW_GAP}`);
    }
    return placed;
  };

  visit(root, 0);
  layout.width = maxDepth * (NODE_WIDTH + GAP_X) + NODE_WIDTH;
  layout.height = Math.max(0, row - 1) * ROW + NODE_HEIGHT + 20;
  return layout;
}
