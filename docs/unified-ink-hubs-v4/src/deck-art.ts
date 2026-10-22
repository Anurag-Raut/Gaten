/** TypeScript helper for production Anki deck thumbnails. Matches the HTML preview. */
export type DeckArtPalette = {id:string;name:string;light:DeckArtColors;dark:DeckArtColors};
export type DeckArtColors = {base:string;primary:string;secondary:string};
export type DeckArtSpec = {id:string;paletteId:string;paletteName:string;palette:DeckArtColors;motif:number;tilt:number;phase:number};

export const DECK_ART_PALETTES: readonly DeckArtPalette[] = [
  {id:'mist-blue',name:'Mist blue',light:{base:'#E9F1FC',primary:'#C7DDF8',secondary:'#DAE8FA'},dark:{base:'#1C2A3B',primary:'#344D6C',secondary:'#263C55'}},
  {id:'soft-blush',name:'Soft blush',light:{base:'#FCECF0',primary:'#F6CAD6',secondary:'#F8DDE5'},dark:{base:'#38232D',primary:'#644051',secondary:'#4C2C39'}},
  {id:'warm-sand',name:'Warm sand',light:{base:'#F9F2E6',primary:'#EEDDC0',secondary:'#F5E7D0'},dark:{base:'#352D20',primary:'#655037',secondary:'#493B29'}},
  {id:'mint',name:'Soft mint',light:{base:'#E9F6EF',primary:'#C9E9DA',secondary:'#D9F0E4'},dark:{base:'#1D332C',primary:'#335C4B',secondary:'#26483C'}},
  {id:'lilac',name:'Soft lilac',light:{base:'#F0EAFD',primary:'#DCCCFB',secondary:'#E8DDFB'},dark:{base:'#2F2641',primary:'#554274',secondary:'#403257'}},
  {id:'powder-teal',name:'Powder teal',light:{base:'#E7F2F4',primary:'#C5E1E5',secondary:'#D6EAED'},dark:{base:'#20343A',primary:'#386069',secondary:'#2B4B52'}},
];
export function hashDeckId(deckId:string):number {
  let h=2166136261; for(let i=0;i<deckId.length;i++){h^=deckId.charCodeAt(i);h=Math.imul(h,16777619);}return h>>>0;
}
export function deckArtSpec(deckId:string,dark=false):DeckArtSpec {
  const h=hashDeckId(deckId);
  const p=DECK_ART_PALETTES[h%DECK_ART_PALETTES.length];
  return {id:deckId,paletteId:p.id,paletteName:p.name,palette:dark?p.dark:p.light,motif:(h>>>6)%5,tilt:(h>>>11)%3-1,phase:(h>>>14)%4};
}

/** Suggested use in existing React Native tree (only if react-native-svg is present):
 * const art = useMemo(()=>deckArtSpec(`${providerAccountId}:${deck.id}`,isDark),[providerAccountId,deck.id,isDark]);
 * <DeckThumbnail art={art} size={44} />
 * Render with Svg, Defs, ClipPath, Rect, Path, Circle, G and Transform as defined in deck-art.js.
 * If react-native-svg is not installed, implement the same 5 shapes in the existing native
 * drawing/Canvas layer. Do NOT add a heavyweight dependency without checking the repo.
 */
