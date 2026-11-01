// Turns an assistant answer into text that is pleasant to hear: citation labels, ids and markdown are written for the
// screen and are never read aloud.

const CITATIONS = /\s*\[\s*S\d+(?:\s*[,;]\s*S\d+)*\s*\]/g
const UUID = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi
const DEVICE_HW_ID = /\besp32-[0-9a-f]{6,12}\b/gi
const URL = /\bhttps?:\/\/\S+/gi

/** The answer as plain spoken text: no [S1] labels, ids, links, code marks or markdown symbols. */
export function speakableText(content: string): string {
  return content
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(CITATIONS, '')
    .replace(UUID, '')
    .replace(DEVICE_HW_ID, '')
    .replace(URL, 'a link')
    .replace(/[`*_#>]+/g, '')
    .replace(/^\s*[-•]\s+/gm, '')
    .replace(/\(\s*(?:id|device|command)?\s*\)/gi, '')
    .replace(/\s+([.,;:!?])/g, '$1')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Splits long text into pieces of at most `max` characters, preferring sentence ends, then commas, then spaces.
 * Browsers cut off or stall on very long utterances, so a long answer is spoken as a queue of short ones.
 */
export function chunkForSpeech(text: string, max = 220): string[] {
  const sentences = text.split(/(?<=[.!?])\s+/).filter(Boolean)
  const chunks: string[] = []
  let current = ''
  const flush = () => {
    if (current.trim()) chunks.push(current.trim())
    current = ''
  }
  for (const sentence of sentences) {
    for (const piece of splitLong(sentence, max)) {
      if (current && current.length + 1 + piece.length > max) flush()
      current = current ? `${current} ${piece}` : piece
    }
  }
  flush()
  return chunks
}

function splitLong(sentence: string, max: number): string[] {
  if (sentence.length <= max) return [sentence]
  const out: string[] = []
  let rest = sentence
  while (rest.length > max) {
    const window = rest.slice(0, max)
    let cut = Math.max(window.lastIndexOf(', '), window.lastIndexOf('; '))
    if (cut < max / 3) cut = window.lastIndexOf(' ')
    if (cut <= 0) cut = max
    out.push(rest.slice(0, cut + 1).trim())
    rest = rest.slice(cut + 1).trim()
  }
  if (rest) out.push(rest)
  return out
}
