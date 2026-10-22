import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const code=readFileSync(new URL('../src/deck-art.js',import.meta.url),'utf8');
const mod=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(code));
const {deckArtSpec,deckArtSVG,hashDeckId,DECK_ART_PALETTES}=mod;

test('FNV deterministic',()=>{assert.equal(hashDeckId('account:deck-123'),hashDeckId('account:deck-123'))});
test('Same id → identical vector markup',()=>{assert.equal(deckArtSVG('account:deck-123'),deckArtSVG('account:deck-123'))});
test('Theme changes tones, not geometry or palette selection',()=>{
  const a=deckArtSpec('a:123',false),b=deckArtSpec('a:123',true);
  assert.equal(a.paletteId,b.paletteId);assert.equal(a.motif,b.motif);assert.equal(a.tilt,b.tilt);assert.equal(a.phase,b.phase);
  assert.notEqual(a.palette.base,b.palette.base);
});
test('Only approved palettes selected',()=>{for(let i=0;i<1000;i++){
  const a=deckArtSpec('account:deck-'+i);assert.ok(DECK_ART_PALETTES.some(p=>p.id===a.paletteId));
  assert.ok(a.motif>=0&&a.motif<5);assert.ok(a.phase>=0&&a.phase<4);
}});
test('Vector markup contains no input interpolation',()=>{
  const svg=deckArtSVG('abc\"/><script>alert(1)</script>');assert.ok(!svg.includes('<script>'));
});
test('Clip ids are distinct for light/dark',()=>{const a=deckArtSVG('abc'),b=deckArtSVG('abc',{dark:true});
  assert.match(a,/deck-clip-\d+-light/);assert.match(b,/deck-clip-\d+-dark/);
});
