import {NativeModules} from "react-native";

interface GatenRecognitionNative {
  isMangaOcrEnabled(): Promise<boolean>;
  setMangaOcrEnabled(enabled: boolean): Promise<void>;
}

const GatenRecognition = NativeModules.GatenRecognition as GatenRecognitionNative;

export const isMangaOcrEnabled = (): Promise<boolean> =>
  GatenRecognition.isMangaOcrEnabled();

export const setMangaOcrEnabled = (enabled: boolean): Promise<void> =>
  GatenRecognition.setMangaOcrEnabled(enabled);
