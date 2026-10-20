/**
 * The theme bridge: the reader's three choices and the colours they resolve to.
 *
 * The colours are resolved on the Kotlin side from the same palette functions the Compose theme
 * used, so JS does no colour maths and renders exactly the tones Compose rendered. `colors()`
 * answers a flat map of `#RRGGBB` strings whose keys are the Material roles. Rejections:
 * `E_THEME_UNKNOWN` for a name the app does not know.
 *
 * Every setter announces the change through the `gaten.themeChanged` device event — listen with
 * [onThemeChanged] and re-read the choices and colours.
 */
import { DeviceEventEmitter, NativeModules } from "react-native";

/** Dark or light, or the device's own setting. */
export type ThemeMode = "System" | "Light" | "Dark";

/** The palette: `Gaten` is the shipped one; the names are the Kotlin enum's. */
export type Palette = "Gaten" | "Mono" | "Cool" | "Warm";

/** The accent: `Indigo` is the app's own and the default. */
export type Accent =
  | "Indigo"
  | "Blue"
  | "Violet"
  | "Teal"
  | "Green"
  | "Rose"
  | "Graphite";

/** The three choices, the shape getTheme() answers with. */
export interface ThemeChoices {
  mode: ThemeMode;
  palette: Palette;
  accent: Accent;
}

/** The resolved scheme, keyed by the Material role. Every value is an opaque `#RRGGBB`. */
export interface ThemeColorMap {
  background: string;
  onBackground: string;
  surface: string;
  onSurface: string;
  surfaceVariant: string;
  onSurfaceVariant: string;
  surfaceContainer: string;
  surfaceContainerHigh: string;
  surfaceContainerHighest: string;
  surfaceContainerLow: string;
  surfaceContainerLowest: string;
  surfaceTint: string;
  outline: string;
  outlineVariant: string;
  primary: string;
  onPrimary: string;
  primaryContainer: string;
  onPrimaryContainer: string;
  secondary: string;
  onSecondary: string;
  secondaryContainer: string;
  onSecondaryContainer: string;
  tertiary: string;
  tertiaryContainer: string;
  onTertiaryContainer: string;
  inverseSurface: string;
  inverseOnSurface: string;
  inversePrimary: string;
  error: string;
  errorContainer: string;
  onErrorContainer: string;
}

interface GatenThemeNative {
  getTheme(): Promise<ThemeChoices>;
  setThemeMode(mode: ThemeMode): Promise<ThemeChoices>;
  setPalette(palette: Palette): Promise<ThemeChoices>;
  setAccent(accent: Accent): Promise<ThemeChoices>;
  setNavigationBarAppearance(background: string, dark: boolean): Promise<void>;
  colors(
    mode: ThemeMode | null,
    palette: Palette | null,
    accent: Accent | null
  ): Promise<ThemeColorMap>;
}

const GatenTheme = NativeModules.GatenTheme as GatenThemeNative;

/** The three choices as stored. */
export function getTheme(): Promise<ThemeChoices> {
  return GatenTheme.getTheme();
}

/** Stores the mode and announces it. */
export function setThemeMode(mode: ThemeMode): Promise<ThemeChoices> {
  return GatenTheme.setThemeMode(mode);
}

/** Stores the palette and announces it. */
export function setPalette(palette: Palette): Promise<ThemeChoices> {
  return GatenTheme.setPalette(palette);
}

/** Stores the accent and announces it. */
export function setAccent(accent: Accent): Promise<ThemeChoices> {
  return GatenTheme.setAccent(accent);
}

/** Applies the RN theme's background and icon contrast to Android's gesture navigation area. */
export function setNavigationBarAppearance(background: string, dark: boolean): Promise<void> {
  return GatenTheme.setNavigationBarAppearance(background, dark);
}

/**
 * The resolved colours for the given choices; an omitted value falls back to the stored choice, and
 * `System` is resolved against the device's own dark setting.
 */
export function colors(
  mode?: ThemeMode,
  palette?: Palette,
  accent?: Accent
): Promise<ThemeColorMap> {
  return GatenTheme.colors(mode ?? null, palette ?? null, accent ?? null);
}

/** Re-renders when the theme changes: re-read the choices and the colours in the listener. */
export function onThemeChanged(listener: () => void): { remove: () => void } {
  return DeviceEventEmitter.addListener("gaten.themeChanged", listener);
}
