import { DatePipe, NgTemplateOutlet } from '@angular/common';
import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from './icon';
import { Logo } from './logo';
import { relativeTime } from './relative-time';
import { layoutTree, MapAction, MapNode, NODE_HEIGHT, NODE_WIDTH } from './tree-layout';

const MIN_ZOOM = 0.3;
const MAX_ZOOM = 2;
/** The first view shows the whole tree, down to this zoom; the user zooms in from there. */
const MIN_FIRST_VIEW = 0.4;
/** Room on the left for the root's circle, which overhangs its card. */
const ROOT_OVERHANG = 64;
const MARGIN = 24;
const COMPACT_QUERY = '(max-width: 47.99rem)';

let nextId = 0;

/**
 * Integration map in the style of Argo CD's resource tree: a pannable, zoomable canvas of node cards joined by
 * dashed arrows. Nodes are links (Tab/Enter), subtrees collapse with +/−, and each node can carry a kebab menu.
 * On phones the same tree renders as a nested list.
 */
@Component({
  selector: 'app-tree-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, NgTemplateOutlet, RouterLink, Icon, Logo],
  templateUrl: './tree-map.html',
  styleUrl: './tree-map.scss',
  host: {
    '(document:click)': 'closeMenuOutside($event)',
    '(document:keydown.escape)': 'closeMenu()',
  },
})
export class TreeMap {
  readonly root = input.required<MapNode>();
  /** Ids that start collapsed (reset when the input changes). */
  readonly initiallyCollapsed = input<readonly string[]>([]);
  readonly label = input('Mapa de integraciones');
  readonly action = output<{ node: MapNode; action: MapAction }>();

  protected readonly collapsed = linkedSignal(() => new Set(this.initiallyCollapsed()));
  protected readonly layout = computed(() => layoutTree(this.root(), this.collapsed()));
  protected readonly view = signal({ x: ROOT_OVERHANG, y: MARGIN, k: 1 });
  protected readonly transform = computed(() => {
    const { x, y, k } = this.view();
    return `translate(${x}px, ${y}px) scale(${k})`;
  });
  protected readonly zoomLabel = computed(() => `${Math.round(this.view().k * 100)} %`);
  protected readonly openMenu = signal<string | null>(null);
  protected readonly compact = signal(false);
  protected readonly markerId = `map-arrow-${++nextId}`;
  protected readonly nodeWidth = NODE_WIDTH;
  protected readonly nodeHeight = NODE_HEIGHT;

  private readonly viewport = viewChild<ElementRef<HTMLElement>>('viewport');
  private centeredFor: string | null = null;
  /** Once the user pans or zooms, data refreshes no longer move the view. */
  private touched = false;
  private drag: { pointerId: number; startX: number; startY: number; x: number; y: number } | null = null;

  constructor() {
    const media = matchMedia(COMPACT_QUERY);
    const sync = () => this.compact.set(media.matches);
    sync();
    media.addEventListener('change', sync);
    inject(DestroyRef).onDestroy(() => media.removeEventListener('change', sync));

    // Fit the tree whenever a new one shows up (a new tenant scope) or it grows before the user touched it.
    afterRenderEffect(() => {
      const id = this.root().id;
      this.layout();
      if (!this.viewport()) {
        return;
      }
      if (id !== this.centeredFor) {
        this.centeredFor = id;
        this.touched = false;
      }
      if (!this.touched) {
        untracked(() => this.frame(MIN_FIRST_VIEW));
      }
    });
  }

  protected isCollapsed(id: string): boolean {
    return this.collapsed().has(id);
  }

  /** Folds or unfolds a subtree; an unfolded column that lands past the right edge is panned into view. */
  protected toggle(node: MapNode): void {
    this.touched = true;
    this.collapsed.update((current) => {
      const next = new Set(current);
      if (!next.delete(node.id)) {
        next.add(node.id);
      }
      return next;
    });
    const box = this.viewportBox();
    const { x, k } = this.view();
    const overflow = x + this.layout().width * k - ((box?.width ?? 0) - MARGIN);
    if (box && overflow > 0) {
      this.view.update((view) => ({ ...view, x: view.x - overflow }));
    }
  }

  protected age(iso: string): string {
    return relativeTime(iso);
  }

  protected describe(node: MapNode): string {
    return [node.kind, node.title, ...node.signals.map((signal) => signal.label), node.note].filter(Boolean).join(', ');
  }

  // ---- Menu ----

  protected toggleMenu(node: MapNode, event: Event): void {
    event.stopPropagation();
    this.openMenu.update((current) => (current === node.id ? null : node.id));
  }

  protected choose(node: MapNode, action: MapAction): void {
    this.openMenu.set(null);
    this.action.emit({ node, action });
  }

  protected closeMenu(): void {
    this.openMenu.set(null);
  }

  protected closeMenuOutside(event: Event): void {
    if (this.openMenu() && !(event.target as HTMLElement | null)?.closest('.menu')) {
      this.openMenu.set(null);
    }
  }

  // ---- Pan & zoom ----

  /** 100 %, root centered vertically (as Argo CD opens a tree). */
  protected center(): void {
    this.touched = true;
    const box = this.viewportBox();
    if (!box) {
      return;
    }
    const { width, nodes } = this.layout();
    const x = width + ROOT_OVERHANG + 2 * MARGIN <= box.width ? (box.width - width + ROOT_OVERHANG) / 2 : ROOT_OVERHANG + MARGIN / 2;
    this.view.set({ x, y: box.height / 2 - (nodes[0]?.y ?? 0) - NODE_HEIGHT / 2, k: 1 });
  }

  protected fit(): void {
    this.touched = true;
    this.frame(MIN_ZOOM);
  }

  /** Scales the whole tree into the canvas (never above 100 % nor below `minZoom`); what still overflows starts at the top left. */
  private frame(minZoom: number): void {
    const box = this.viewportBox();
    if (!box) {
      return;
    }
    const { width, height } = this.layout();
    const fullWidth = width + ROOT_OVERHANG;
    const k = clamp(Math.min((box.width - 2 * MARGIN) / fullWidth, (box.height - 2 * MARGIN) / height), minZoom, 1);
    const x = fullWidth * k + 2 * MARGIN <= box.width ? (box.width - fullWidth * k) / 2 + ROOT_OVERHANG * k : ROOT_OVERHANG * k + MARGIN;
    const y = height * k + 2 * MARGIN <= box.height ? (box.height - height * k) / 2 : MARGIN;
    this.view.set({ x, y, k });
  }

  protected zoomBy(factor: number, at?: { x: number; y: number }): void {
    this.touched = true;
    const box = this.viewportBox();
    if (!box) {
      return;
    }
    const origin = at ?? { x: box.width / 2, y: box.height / 2 };
    this.view.update(({ x, y, k }) => {
      const next = clamp(k * factor, MIN_ZOOM, MAX_ZOOM);
      return { x: origin.x - ((origin.x - x) * next) / k, y: origin.y - ((origin.y - y) * next) / k, k: next };
    });
  }

  protected panBy(dx: number, dy: number): void {
    this.touched = true;
    this.view.update((view) => ({ ...view, x: view.x + dx, y: view.y + dy }));
  }

  protected onWheel(event: WheelEvent): void {
    event.preventDefault();
    if (event.ctrlKey || event.metaKey) {
      const box = this.viewportBox();
      this.zoomBy(Math.exp(-event.deltaY * 0.0025), box ? { x: event.clientX - box.left, y: event.clientY - box.top } : undefined);
    } else {
      this.panBy(-event.deltaX, -event.deltaY);
    }
  }

  protected onPointerDown(event: PointerEvent): void {
    if (event.button !== 0 || (event.target as HTMLElement).closest('.node, .tools')) {
      return;
    }
    const { x, y } = this.view();
    this.touched = true;
    this.drag = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, x, y };
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  protected onPointerMove(event: PointerEvent): void {
    if (this.drag?.pointerId === event.pointerId) {
      const { startX, startY, x, y } = this.drag;
      this.view.update((view) => ({ ...view, x: x + event.clientX - startX, y: y + event.clientY - startY }));
    }
  }

  protected onPointerUp(): void {
    this.drag = null;
  }

  /** Arrow keys pan, + and − zoom, 0 recenters (when the canvas itself has focus). */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.target !== event.currentTarget) {
      return;
    }
    const step = 80;
    const moves: Record<string, () => void> = {
      ArrowLeft: () => this.panBy(step, 0),
      ArrowRight: () => this.panBy(-step, 0),
      ArrowUp: () => this.panBy(0, step),
      ArrowDown: () => this.panBy(0, -step),
      '+': () => this.zoomBy(1.2),
      '=': () => this.zoomBy(1.2),
      '-': () => this.zoomBy(1 / 1.2),
      '0': () => this.center(),
    };
    const move = moves[event.key];
    if (move) {
      event.preventDefault();
      move();
    }
  }

  /** Keeps the node that receives keyboard focus inside the visible canvas. */
  protected revealFocused(event: FocusEvent): void {
    const node = (event.target as HTMLElement).closest<HTMLElement>('.node');
    const box = this.viewportBox();
    if (!node || !box) {
      return;
    }
    const rect = node.getBoundingClientRect();
    const dx = rect.left < box.left + MARGIN ? box.left + MARGIN - rect.left : rect.right > box.right - MARGIN ? box.right - MARGIN - rect.right : 0;
    const dy = rect.top < box.top + MARGIN ? box.top + MARGIN - rect.top : rect.bottom > box.bottom - MARGIN ? box.bottom - MARGIN - rect.bottom : 0;
    if (dx || dy) {
      this.panBy(dx, dy);
    }
  }

  private viewportBox(): DOMRect | null {
    return this.viewport()?.nativeElement.getBoundingClientRect() ?? null;
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
