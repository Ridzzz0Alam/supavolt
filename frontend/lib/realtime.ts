import {
  HubConnection,
  HubConnectionBuilder,
  HubConnectionState,
  LogLevel,
} from '@microsoft/signalr';

/**
 * Drop-in replacement for the socket.io realtime client.
 *
 * Mapping from the original:
 *   socket.emit('subscribe', table)   -> connection.invoke('Subscribe', table)
 *   socket.on('event', handler)       -> connection.on('event', handler)
 *   auth: { token: anonKey }          -> accessTokenFactory: () => anonKey
 *
 * The hub is mounted at /realtime on the API host, outside the /api path base.
 */

export type RealtimeEventType = 'insert' | 'update' | 'delete';

export interface RealtimeEvent {
  type: RealtimeEventType;
  table: string;
  record: Record<string, unknown> | null;
  oldRecord: Record<string, unknown> | null;
  projectId: string;
  timestamp: string;
}

export type RealtimeCallback = (event: RealtimeEvent) => void;

function hubUrl(): string {
  const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:5000/api';
  return `${apiUrl.replace(/\/api\/?$/, '')}/realtime`;
}

export class SupavoltRealtime {
  private connection: HubConnection | null = null;
  private readonly callbacks = new Map<string, Set<RealtimeCallback>>();

  constructor(private readonly anonKey: string) {}

  private async connect(): Promise<HubConnection> {
    if (this.connection?.state === HubConnectionState.Connected) return this.connection;

    if (!this.connection) {
      this.connection = new HubConnectionBuilder()
        .withUrl(hubUrl(), { accessTokenFactory: () => this.anonKey })
        .withAutomaticReconnect()
        .configureLogging(LogLevel.Warning)
        .build();

      this.connection.on('event', (event: RealtimeEvent) => {
        this.callbacks.get(event.table)?.forEach((cb) => cb(event));
      });

      // Re-subscribe after a reconnect: group membership lives on the server connection.
      this.connection.onreconnected(async () => {
        for (const table of this.callbacks.keys()) {
          await this.connection?.invoke('Subscribe', table);
        }
      });
    }

    if (this.connection.state === HubConnectionState.Disconnected) {
      await this.connection.start();
    }

    return this.connection;
  }

  /** Returns an unsubscribe function, matching the original SDK's contract. */
  async subscribe(table: string, callback: RealtimeCallback): Promise<() => void> {
    const connection = await this.connect();
    const isNewTable = !this.callbacks.has(table);

    if (isNewTable) this.callbacks.set(table, new Set());
    this.callbacks.get(table)!.add(callback);

    if (isNewTable) await connection.invoke('Subscribe', table);

    return () => void this.unsubscribe(table, callback);
  }

  async unsubscribe(table: string, callback?: RealtimeCallback): Promise<void> {
    const handlers = this.callbacks.get(table);
    if (!handlers) return;

    if (callback) handlers.delete(callback);
    else handlers.clear();

    if (handlers.size === 0) {
      this.callbacks.delete(table);
      if (this.connection?.state === HubConnectionState.Connected) {
        await this.connection.invoke('Unsubscribe', table);
      }
    }
  }

  async disconnect(): Promise<void> {
    this.callbacks.clear();
    await this.connection?.stop();
    this.connection = null;
  }

  get connected(): boolean {
    return this.connection?.state === HubConnectionState.Connected;
  }
}
