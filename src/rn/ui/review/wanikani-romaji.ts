/** Convert the romaji a reader types into hiragana while they are entering a reading. */
const ROMAJI: Record<string, string> = {
  kya: "きゃ", kyu: "きゅ", kyo: "きょ", sha: "しゃ", shu: "しゅ", sho: "しょ",
  cha: "ちゃ", chu: "ちゅ", cho: "ちょ", nya: "にゃ", nyu: "にゅ", nyo: "にょ",
  hya: "ひゃ", hyu: "ひゅ", hyo: "ひょ", mya: "みゃ", myu: "みゅ", myo: "みょ",
  rya: "りゃ", ryu: "りゅ", ryo: "りょ", gya: "ぎゃ", gyu: "ぎゅ", gyo: "ぎょ",
  ja: "じゃ", ju: "じゅ", jo: "じょ", bya: "びゃ", byu: "びゅ", byo: "びょ",
  pya: "ぴゃ", pyu: "ぴゅ", pyo: "ぴょ", fa: "ふぁ", fi: "ふぃ", fe: "ふぇ", fo: "ふぉ",
  va: "ゔぁ", vi: "ゔぃ", vu: "ゔ", ve: "ゔぇ", vo: "ゔぉ",
  shi: "し", chi: "ち", tsu: "つ", fu: "ふ", hu: "ふ", ji: "じ", zu: "ず",
  a: "あ", i: "い", u: "う", e: "え", o: "お",
  ka: "か", ki: "き", ku: "く", ke: "け", ko: "こ", ga: "が", gi: "ぎ", gu: "ぐ", ge: "げ", go: "ご",
  sa: "さ", si: "し", su: "す", se: "せ", so: "そ", za: "ざ", zi: "じ", ze: "ぜ", zo: "ぞ",
  ta: "た", ti: "ち", tu: "つ", te: "て", to: "と", da: "だ", di: "ぢ", du: "づ", de: "で", do: "ど",
  na: "な", ni: "に", nu: "ぬ", ne: "ね", no: "の", ha: "は", hi: "ひ", he: "へ", ho: "ほ",
  ma: "ま", mi: "み", mu: "む", me: "め", mo: "も", ya: "や", yu: "ゆ", yo: "よ",
  ra: "ら", ri: "り", ru: "る", re: "れ", ro: "ろ", wa: "わ", wi: "ゐ", we: "ゑ", wo: "を",
  ba: "ば", bi: "び", bu: "ぶ", be: "べ", bo: "ぼ", pa: "ぱ", pi: "ぴ", pu: "ぷ", pe: "ぺ", po: "ぽ",
};

const TOKENS = Object.keys(ROMAJI).sort((a, b) => b.length - a.length);

/** Convert complete syllables while typing; on submission, also finish an ambiguous trailing n. */
export function romajiToHiragana(text: string, final = false): string {
  return text.replace(/[A-Za-z']+/g, (run) => {
    const lower = run.toLowerCase();
    let result = "";
    let index = 0;
    while (index < lower.length) {
      const current = lower[index];
      const next = lower[index + 1];
      if (current === "n") {
        if (final && next == null) {
          result += "ん";
          index += 1;
          continue;
        }
        if (next === "'") {
          result += "ん";
          index += 2;
          continue;
        }
        if (next === "n") {
          result += "ん";
          // Before a vowel/y, keep the second n so `nni` can become んに. At the end
          // of a run, the pair is the usual unambiguous spelling of ん.
          index += lower[index + 2] != null && "aeiouy".includes(lower[index + 2]) ? 1 : 2;
          continue;
        }
        // A consonant after n closes the nasal syllable. Keep n at the end, or before a
        // vowel/y, because the next keystroke may turn it into na/nya/etc.
        if (next != null && !"aeiouy".includes(next)) {
          result += "ん";
          index += 1;
          continue;
        }
      }
      if (index + 1 < lower.length && current === next && !"aeiou".includes(current)) {
        result += "っ";
        index += 1;
        continue;
      }
      const token = TOKENS.find((candidate) => lower.startsWith(candidate, index));
      if (token != null) {
        result += ROMAJI[token];
        index += token.length;
      } else {
        result += run[index];
        index += 1;
      }
    }
    return result;
  });
}
