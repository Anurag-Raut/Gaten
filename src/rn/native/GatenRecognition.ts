import {NativeModules} from "react-native";

/**
 * Any of the selectable engines; also the persisted selected mode. `ml_kit` is bundled with the app
 * and always installed; `manga_ocr`, `medium` and `manga` are downloaded on demand.
 */
export type RecognitionEngine = "ml_kit" | "manga_ocr" | "medium" | "manga";

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

interface GatenRecognitionNative {
  isMangaOcrEnabled(): Promise<boolean>;
  setMangaOcrEnabled(enabled: boolean): Promise<void>;
  getRecognitionSettings(): Promise<RecognitionSettings>;
  setRecognitionMode(mode: RecognitionEngine): Promise<void>;
  downloadRecognitionEngine(engine: RecognitionEngine): Promise<RecognitionSettings>;
}

const GatenRecognition = NativeModules.GatenRecognition as GatenRecognitionNative;

export const isMangaOcrEnabled = (): Promise<boolean> =>
  GatenRecognition.isMangaOcrEnabled();

export const setMangaOcrEnabled = (enabled: boolean): Promise<void> =>
  GatenRecognition.setMangaOcrEnabled(enabled);

export const getRecognitionSettings = (): Promise<RecognitionSettings> =>
  GatenRecognition.getRecognitionSettings();

export const setRecognitionMode = (mode: RecognitionEngine): Promise<void> =>
  GatenRecognition.setRecognitionMode(mode);

/** Explicit download (the screen confirms each tap); answers with the refreshed install state. */
export const downloadRecognitionEngine = (
  engine: RecognitionEngine,
): Promise<RecognitionSettings> =>
  GatenRecognition.downloadRecognitionEngine(engine);
