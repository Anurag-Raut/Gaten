import {NativeModules} from "react-native";

/** Any of the three on-demand engines; also the persisted selected mode. */
export type RecognitionEngine = "manga_ocr" | "medium" | "manga";

/** One engine as the settings screen shows it. */
export interface EngineStatus {
  id: RecognitionEngine;
  name: string;
  downloadBytes: number;
  installed: boolean;
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
