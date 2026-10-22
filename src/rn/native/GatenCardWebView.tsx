/**
 * The locked-down card WebView the Compose screens drew with AndroidView: AnkiDroid's card HTML
 * and a radical's SVG, rendered by a WebView that can do nothing but draw. The configuration the
 * spec fixes — no JavaScript, no file or content access, no DOM storage, no network loads, no
 * zoom, every navigation overridden — lives on the Kotlin side (GatenCardWebViewManager); this
 * wrapper only carries the document, the surface colour behind it, and the height the card
 * content measured at.
 */
import React from "react";
import {requireNativeComponent, type StyleProp, type ViewStyle} from "react-native";

/** The props the native view manager reads. */
export interface GatenCardWebViewNativeProps {
  /** The full HTML document (cardDocument or the radical SVG shell), loaded via loadDataWithBaseURL. */
  document: string;
  /** The theme surface colour behind the card, as "#RRGGBB" — repainted on every update. */
  backgroundColor: string;
  /**
   * "card" measures its content and reports `onCardHeight`; "svg" draws at whatever height the
   * style gives it and reports nothing.
   */
  mode?: "card" | "svg";
  /** The measured content height, in dp. Emitted only when it changes by more than 1px. */
  onCardHeight?: (event: {nativeEvent: {height: number}}) => void;
  style?: StyleProp<ViewStyle>;
}

const NativeCardWebView =
  requireNativeComponent<GatenCardWebViewNativeProps>("GatenCardWebView");

/** The props the screens pass. */
export interface GatenCardWebViewProps {
  document: string;
  backgroundColor: string;
  mode?: "card" | "svg";
  onCardHeight?: (heightDp: number) => void;
  style?: StyleProp<ViewStyle>;
}

/**
 * The card's own rendered HTML on Gaten's surface. Loading is cached on the native side (the
 * document is the cache key), so recomposing with the same document never reloads the card.
 */
export function GatenCardWebView({
  document,
  backgroundColor,
  mode = "card",
  onCardHeight,
  style,
}: GatenCardWebViewProps) {
  return (
    <NativeCardWebView
      document={document}
      backgroundColor={backgroundColor}
      mode={mode}
      onCardHeight={
        onCardHeight == null
          ? undefined
          : (event) => onCardHeight(event.nativeEvent.height)
      }
      style={style}
    />
  );
}