import {NativeEventEmitter, NativeModules} from "react-native";

/**
 * Any of the selectable engines; also the persisted selected mode. `ml_kit` is bundled with the app
 * and always installed; `medium` (PP-OCRv6 Medium) is the one downloadable engine, recommended for
 * screen and novel text.
 */
export type RecognitionEngine = "ml_kit" | "medium";

/** One engine as the settings screen shows it. */
export interface EngineStatus {
  id: RecognitionEngine;
  name: string;
  /** The download size in bytes; 0 for the bundled engine, which is never downloaded. */
  downloadBytes: number;
  installed: boolean;
  /** Bundled with the app: nothing to download, and always installed. */
  bundled: boolean;
  /** The one the settings screen marks "Recommended". */
  recommended: boolean;
}

export interface RecognitionSettings {
  mode: RecognitionEngine;
  engines: EngineStatus[];
  /** Engine ids with a download running, so a reopened screen shows it running. */
  downloading: RecognitionEngine[];
}

/**
 * One live report from a running download. `bytesTotal` is the engine's real expected size, so a
 * `bytesDone`/`bytesTotal` ratio is the bar; the phase turns to "verifying" once every byte is in.
 */
export type DownloadProgress = {
  engine: "medium";
  phase: "downloading" | "verifying";
  bytesDone: number;
  bytesTotal: number;
};

/** The failure kinds a download rejection is distilled into, one per actionable remedy. */
export type DownloadFailure = "offline" | "storage" | "verify" | "cancelled" | "unknown";

interface GatenRecognitionNative {
  getRecognitionSettings(): Promise<RecognitionSettings>;
  setRecognitionMode(mode: RecognitionEngine): Promise<void>;
  downloadRecognitionEngine(engine: RecognitionEngine): Promise<RecognitionSettings>;
  cancelDownload(): Promise<void>;
  /**
   * Kept for React Native's `NativeEventEmitter`: the bridge calls these as listeners come and go.
   * Only present so the emitter does not warn; the events themselves arrive through the device
   * emitter.
   */
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const GatenRecognition = NativeModules.GatenRecognition as GatenRecognitionNative;

/** The same module, seen as possibly absent, so a subscription can no-op off-device. */
const nativeRecognition = NativeModules.GatenRecognition as GatenRecognitionNative | undefined;

/** The native event name; the payload is a [DownloadProgress]. */
const DOWNLOAD_PROGRESS_EVENT = "GatenRecognitionDownloadProgress";

const progressEmitter =
  nativeRecognition != null ? new NativeEventEmitter(nativeRecognition) : null;

export const getRecognitionSettings = (): Promise<RecognitionSettings> =>
  GatenRecognition.getRecognitionSettings();

export const setRecognitionMode = (mode: RecognitionEngine): Promise<void> =>
  GatenRecognition.setRecognitionMode(mode);

/** Explicit download (the screen confirms each tap); answers with the refreshed install state. */
export const downloadRecognitionEngine = (
  engine: RecognitionEngine,
): Promise<RecognitionSettings> =>
  GatenRecognition.downloadRecognitionEngine(engine);

/**
 * Aborts the download running now, if any; the pending [downloadRecognitionEngine] promise rejects
 * with a `DOWNLOAD_CANCELLED` code, which [classifyDownloadError] reads as "cancelled". With
 * nothing in flight it resolves quietly.
 */
export function cancelDownload(): Promise<void> {
  if (nativeRecognition == null) {
    return Promise.reject(new Error("GatenRecognition is not available on this platform."));
  }
  return nativeRecognition.cancelDownload();
}

/**
 * Listens for download progress and returns the unsubscribe. Safe where the native module is
 * absent: the call then no-ops and the returned function is a no-op too, so a screen can subscribe
 * unconditionally and only unsubscribe on unmount.
 */
export function subscribeDownloadProgress(
  listener: (progress: DownloadProgress) => void,
): () => void {
  if (progressEmitter == null) return () => {};
  const subscription = progressEmitter.addListener(DOWNLOAD_PROGRESS_EVENT, listener);
  return () => subscription.remove();
}

/** The `code` React Native attaches to a rejected promise, when there is one. */
function rejectionCode(error: unknown): string | undefined {
  if (typeof error === "object" && error !== null) {
    const code = (error as {code?: unknown}).code;
    if (typeof code === "string") return code;
  }
  return undefined;
}

/**
 * Maps a download rejection to the kind the UI branches on. Anything unrecognized — a non-error, an
 * unexpected code, or the generic native fallback — is "unknown", never a guess.
 */
export function classifyDownloadError(error: unknown): DownloadFailure {
  switch (rejectionCode(error)) {
    case "DOWNLOAD_OFFLINE":
      return "offline";
    case "DOWNLOAD_NO_SPACE":
      return "storage";
    case "DOWNLOAD_VERIFY_FAILED":
      return "verify";
    case "DOWNLOAD_CANCELLED":
      return "cancelled";
    default:
      return "unknown";
  }
}
