/** Gaten Ink procedural Anki deck artwork. Deterministic, stateless, no network/no assets. */
export const DECK_ART_PALETTES = Object.freeze([
  { id:'mist-blue',  name:'Mist blue',  light:{base:'#E9F1FC',primary:'#C7DDF8',secondary:'#DAE8FA'},dark:{base:'#1C2A3B',primary:'#344D6C',secondary:'#263C55'} },
  { id:'soft-blush', name:'Soft blush', light:{base:'#FCECF0',primary:'#F6CAD6',secondary:'#F8DDE5'},dark:{base:'#38232D',primary:'#644051',secondary:'#4C2C39'} },
  { id:'warm-sand',  name:'Warm sand',  light:{base:'#F9F2E6',primary:'#EEDDC0',secondary:'#F5E7D0'},dark:{base:'#352D20',primary:'#655037',secondary:'#493B29'} },
  { id:'mint',       name:'Soft mint',  light:{base:'#E9F6EF',primary:'#C9E9DA',secondary:'#D9F0E4'},dark:{base:'#1D332C',primary:'#335C4B',secondary:'#26483C'} },
  { id:'lilac',      name:'Soft lilac', light:{base:'#F0EAFD',primary:'#DCCCFB',secondary:'#E8DDFB'},dark:{base:'#2F2641',primary:'#554274',secondary:'#403257'} },
  { id:'powder-teal',name:'Powder teal',light:{base:'#E7F2F4',primary:'#C5E1E5',secondary:'#D6EAED'},dark:{base:'#20343A',primary:'#386069',secondary:'#2B4B52'} },
]);

/** Hash UTF-16 code units; same identifier -> same unsigned 32-bit result. */
export function hashDeckId(deckId) {
  const str = String(deckId ?? '');
  let h = 2166136261;
  for (let i=0;i<str.length;i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h,16777619);
  }
  return h >>> 0;
}

/**
 * Persistent art assignment: id controls palette, motif, orientation.
 * User account/provider can be prefixed in calling code to avoid id collisions.
 * Do NOT use queue count, time, array index, appearance accent, or random().
 */
export function deckArtSpec(deckId, dark=false) {
  const h=hashDeckId(deckId);
  const palette=DECK_ART_PALETTES[h % DECK_ART_PALETTES.length];
  return {id:String(deckId),paletteId:palette.id,paletteName:palette.name,
    palette:dark?palette.dark:palette.light,
    motif:(h>>>6)%5,
    tilt:(h>>>11)%3-1,
    phase:(h>>>14)%4,
  };
}

/** Safe SVG markup; no input embedded inside markup. Artwork is decorative. */
export function deckArtSVG(deckId,{dark=false,size=44}={}) {
  const {palette:c,motif,tilt,phase}=deckArtSpec(deckId,dark);
  // The fixed pattern templates are clean and intentional; deterministic transformations
  // provide recognizably varied compositions without noisy pseudo-random drawings.
  const shapes=[
    // Sweeping folio arc, cropped by edge
    `<path d="M-12 66C-7 32 14 19 35 21C54 23 68 8 79-13" fill="none" stroke="${c.primary}" stroke-width="18"/><path d="M-13 72C5 45 23 38 45 42C63 45 69 34 80 19" fill="none" stroke="${c.secondary}" stroke-width="11"/>`,
    // Two soft nested half circles
    `<circle cx="67" cy="70" r="55" fill="${c.primary}"/><circle cx="71" cy="69" r="39" fill="${c.secondary}"/><circle cx="73" cy="69" r="25" fill="${c.base}" opacity=".55"/>`,
    // Organic leaves using only 2-3 large curves
    `<path d="M-12 72C-5 28 24 23 37 36C44 42 45 52 41 75Z" fill="${c.primary}"/><path d="M11 74C17 43 44 20 76 12C68 46 49 74 11 74Z" fill="${c.secondary}"/>`,
    // Quiet diagonal ribbon
    `<path d="M-21 48L45-17L69 7L3 73Z" fill="${c.primary}"/><path d="M12 85L81 18L92 30L24 98Z" fill="${c.secondary}"/>`,
    // Soft horizon / rising sun
    `<circle cx="43" cy="58" r="37" fill="${c.primary}"/><path d="M-6 55Q20 37 44 53T74 47V76H-6Z" fill="${c.secondary}"/>`
  ];
  const rotation=tilt*6; const horizontal=phase%2===0?0:4; const vertical=phase>=2?2:-2;
  const clipId=`deck-clip-${hashDeckId(deckId)}-${dark?'dark':'light'}`;
  const edge=Math.max(28,Math.min(128,Number(size)||44));
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${edge}" height="${edge}" viewBox="0 0 64 64" fill="none" aria-hidden="true" focusable="false"><defs><clipPath id="${clipId}"><rect width="64" height="64" rx="12"/></clipPath></defs><g clip-path="url(#${clipId})"><rect width="64" height="64" fill="${c.base}"/><g transform="translate(${horizontal} ${vertical}) rotate(${rotation} 32 32)">${shapes[motif]}</g></g></svg>`;
}
