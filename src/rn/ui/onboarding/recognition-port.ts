/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The onboarding flow's one door to the recognition module: the settings read and the mode write it
 * already had, plus the PP-OCRv6 Medium download with its progress, its cancel and its failure
 * classifier.
 *
 * The page talks to this small surface rather than to the native module directly, so the engine a
 * download targets is named once ("medium"), the module's progress and failure types are re-exported
 * from one place, and the page's logic is a function of these calls and nothing else.
 */
import {
  cancelDownload as nativeCancelDownload,
  classifyDownloadError as nativeClassifyDownloadError,
  downloadRecognitionEngine,
  getRecognitionSettings as nativeGetRecognitionSettings,
  setRecognitionMode as nativeSetRecognitionMode,
  subscribeDownloadProgress as nativeSubscribeDownloadProgress,
} from "../../native/GatenRecognition";
import type {
  DownloadFailure,
  DownloadProgress,
  RecognitionEngine,
  RecognitionSettings,
} from "../../native/GatenRecognition";

export type {DownloadFailure, DownloadProgress, RecognitionEngine, RecognitionSettings};

/** The reader's engines and the selected one. */
export const getRecognitionSettings = (): Promise<RecognitionSettings> =>
  nativeGetRecognitionSettings();

/** Stores the selected engine. */
export const setRecognitionMode = (mode: RecognitionEngine): Promise<void> =>
  nativeSetRecognitionMode(mode);

/** Downloads PP-OCRv6 Medium. Resolves when it is installed, rejects with the module's own code. */
export const downloadMedium = (): Promise<void> =>
  downloadRecognitionEngine("medium").then(() => undefined);

/** Subscribes to PP-OCRv6 Medium's progress; returns the unsubscribe. */
export const subscribeDownloadProgress = (
  listener: (progress: DownloadProgress) => void,
): (() => void) => nativeSubscribeDownloadProgress(listener);

/** Aborts the download running now; a quiet no-op when nothing is in flight. */
export const cancelDownload = (): Promise<void> => nativeCancelDownload();

/** Distils a download rejection into the one kind the copy branches on. */
export const classifyDownloadError = (error: unknown): DownloadFailure =>
  nativeClassifyDownloadError(error);
