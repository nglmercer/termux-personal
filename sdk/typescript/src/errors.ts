export class ProtocolError extends Error {
  readonly outcomeUnknown: boolean;
  constructor(message: string, outcomeUnknown = false) { super(message); this.outcomeUnknown = outcomeUnknown; }
}
export class UnavailableError extends Error {}
export class TransportError extends Error {
  readonly outcomeUnknown: boolean;
  constructor(message: string, outcomeUnknown = false, options?: ErrorOptions) {
    super(message, options);
    this.outcomeUnknown = outcomeUnknown;
  }
}
export class RpcError extends Error {
  readonly outcomeUnknown: boolean;
  readonly code: number;
  readonly data: unknown;
  constructor(code: number, message: string, data?: unknown) {
    super(message);
    this.code = code; this.data = data;
    this.outcomeUnknown = message === "OUTCOME_UNKNOWN" ||
      (typeof data === "object" && data !== null && "code" in data && data.code === "OUTCOME_UNKNOWN");
  }
}
