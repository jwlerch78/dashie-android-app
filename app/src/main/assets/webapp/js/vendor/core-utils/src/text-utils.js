/**
 * @dashieapp/core-utils/text-utils
 *
 * Text processing utilities for voice/NLP.
 * Shared between main Dashie and Dashie Kiosk.
 */

// Word-to-number mappings
const WORD_NUMBERS_ONES = {
  'zero': 0, 'one': 1, 'two': 2, 'three': 3, 'four': 4, 'five': 5,
  'six': 6, 'seven': 7, 'eight': 8, 'nine': 9, 'ten': 10,
  'eleven': 11, 'twelve': 12, 'thirteen': 13, 'fourteen': 14, 'fifteen': 15,
  'sixteen': 16, 'seventeen': 17, 'eighteen': 18, 'nineteen': 19
};

const WORD_NUMBERS_TENS = {
  'twenty': 20, 'thirty': 30, 'forty': 40, 'fifty': 50,
  'sixty': 60, 'seventy': 70, 'eighty': 80, 'ninety': 90
};

/**
 * Convert word numbers to digits in text.
 * Handles single words ("five" → "5") and compound numbers ("twenty five" → "25").
 *
 * @param {string} text - Input text
 * @returns {string} Text with word numbers converted to digits
 */
export function normalizeWordNumbers(text) {
  let result = text.toLowerCase();

  // Handle compound numbers like "twenty five" or "twenty-five"
  for (const [tenWord, tenVal] of Object.entries(WORD_NUMBERS_TENS)) {
    for (const [oneWord, oneVal] of Object.entries(WORD_NUMBERS_ONES)) {
      if (oneVal > 0 && oneVal < 10) {
        // Match "twenty five", "twenty-five", "twentyfive"
        const pattern = new RegExp(`\\b${tenWord}[\\s-]?${oneWord}\\b`, 'gi');
        result = result.replace(pattern, String(tenVal + oneVal));
      }
    }
    // Handle just "twenty", "thirty", etc.
    result = result.replace(new RegExp(`\\b${tenWord}\\b`, 'gi'), String(tenVal));
  }

  // Handle single word numbers
  for (const [word, num] of Object.entries(WORD_NUMBERS_ONES)) {
    result = result.replace(new RegExp(`\\b${word}\\b`, 'gi'), String(num));
  }

  return result;
}
