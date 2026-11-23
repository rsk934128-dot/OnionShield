// Self-contained ISO/IEC 18004 QR Code Generator (Byte Mode, ECC Level M, Versions 1-12)
// Zero external network requests — runs 100% client-side for privacy.
(function (global) {
  'use strict';

  const ALIGN_POSITIONS = {
    1: [],
    2: [6, 18],
    3: [6, 22],
    4: [6, 26],
    5: [6, 30],
    6: [6, 34],
    7: [6, 22, 38],
    8: [6, 24, 42],
    9: [6, 26, 46],
    10: [6, 28, 50],
    11: [6, 30, 54],
    12: [6, 32, 58],
  };

  // Level M block specifications: [totalDataCodewords, ecPerBlock, g1Count, g1Data, g2Count, g2Data]
  const VERSION_SPECS_M = {
    1: [16, 10, 1, 16, 0, 0],
    2: [28, 16, 1, 28, 0, 0],
    3: [44, 26, 1, 44, 0, 0],
    4: [64, 18, 2, 32, 0, 0],
    5: [86, 24, 2, 43, 0, 0],
    6: [108, 16, 4, 27, 0, 0],
    7: [124, 18, 4, 31, 0, 0],
    8: [154, 22, 2, 38, 2, 39],
    9: [182, 22, 3, 36, 2, 37],
    10: [216, 26, 4, 43, 1, 44],
    11: [254, 30, 1, 50, 4, 51],
    12: [290, 22, 6, 36, 2, 37],
  };

  const GF_EXP = new Uint8Array(512);
  const GF_LOG = new Uint8Array(256);
  (function initGaloisField() {
    let x = 1;
    for (let i = 0; i < 255; i++) {
      GF_EXP[i] = x;
      GF_LOG[x] = i;
      x <<= 1;
      if (x & 0x100) x ^= 0x11d;
    }
    for (let i = 255; i < 512; i++) {
      GF_EXP[i] = GF_EXP[i - 255];
    }
  })();

  function gfMul(a, b) {
    if (a === 0 || b === 0) return 0;
    return GF_EXP[GF_LOG[a] + GF_LOG[b]];
  }

  function rsGeneratorPoly(degree) {
    let poly = [1];
    for (let i = 0; i < degree; i++) {
      const next = new Array(poly.length + 1).fill(0);
      const factor = GF_EXP[i];
      for (let j = 0; j < poly.length; j++) {
        next[j] ^= poly[j];
        next[j + 1] ^= gfMul(poly[j], factor);
      }
      poly = next;
    }
    return poly;
  }

  function rsComputeRemainder(data, ecCount) {
    const gen = rsGeneratorPoly(ecCount);
    const res = new Uint8Array(data.length + ecCount);
    res.set(data);
    for (let i = 0; i < data.length; i++) {
      const coef = res[i];
      if (coef !== 0) {
        for (let j = 0; j < gen.length; j++) {
          res[i + j] ^= gfMul(gen[j], coef);
        }
      }
    }
    return res.slice(data.length);
  }

  function encodeDataBits(bytes, version, totalDataCodewords) {
    const bits = [];
    const pushBits = (val, len) => {
      for (let i = len - 1; i >= 0; i--) {
        bits.push((val >>> i) & 1);
      }
    };

    // Byte mode indicator (0100)
    pushBits(0b0100, 4);
    // Character count indicator: 8 bits for V1-9, 16 bits for V10+
    const countBits = version < 10 ? 8 : 16;
    pushBits(bytes.length, countBits);

    for (let i = 0; i < bytes.length; i++) {
      pushBits(bytes[i], 8);
    }

    const totalDataBits = totalDataCodewords * 8;
    const terminatorLen = Math.min(4, totalDataBits - bits.length);
    for (let i = 0; i < terminatorLen; i++) bits.push(0);

    while (bits.length % 8 !== 0) bits.push(0);

    const codewords = [];
    for (let i = 0; i < bits.length; i += 8) {
      let b = 0;
      for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
      codewords.push(b);
    }

    let padIdx = 0;
    const padBytes = [0xec, 0x11];
    while (codewords.length < totalDataCodewords) {
      codewords.push(padBytes[padIdx % 2]);
      padIdx++;
    }
    return new Uint8Array(codewords);
  }

  function interleaveBlocks(dataCodewords, spec) {
    const [, ecPerBlock, g1Count, g1Data, g2Count, g2Data] = spec;
    const dataBlocks = [];
    const ecBlocks = [];
    let offset = 0;

    for (let i = 0; i < g1Count; i++) {
      const slice = dataCodewords.slice(offset, offset + g1Data);
      dataBlocks.push(slice);
      ecBlocks.push(rsComputeRemainder(slice, ecPerBlock));
      offset += g1Data;
    }
    for (let i = 0; i < g2Count; i++) {
      const slice = dataCodewords.slice(offset, offset + g2Data);
      dataBlocks.push(slice);
      ecBlocks.push(rsComputeRemainder(slice, ecPerBlock));
      offset += g2Data;
    }

    const result = [];
    const maxDataLen = Math.max(g1Data, g2Data);
    for (let col = 0; col < maxDataLen; col++) {
      for (let b = 0; b < dataBlocks.length; b++) {
        if (col < dataBlocks[b].length) result.push(dataBlocks[b][col]);
      }
    }
    for (let col = 0; col < ecPerBlock; col++) {
      for (let b = 0; b < ecBlocks.length; b++) {
        result.push(ecBlocks[b][col]);
      }
    }
    return new Uint8Array(result);
  }

  function createEmptyMatrix(version) {
    const size = 17 + 4 * version;
    const modules = Array.from({ length: size }, () => new Int8Array(size));
    const isFunction = Array.from({ length: size }, () => new Uint8Array(size));

    function setFunctionModule(r, c, dark) {
      modules[r][c] = dark ? 1 : 0;
      isFunction[r][c] = 1;
    }

    function drawFinder(top, left) {
      for (let dy = -1; dy <= 7; dy++) {
        for (let dx = -1; dx <= 7; dx++) {
          const r = top + dy;
          const c = left + dx;
          if (r < 0 || r >= size || c < 0 || c >= size) continue;
          const dist = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
          const dark = dist !== 2 && dist !== 4;
          setFunctionModule(r, c, dark);
        }
      }
    }

    drawFinder(0, 0);
    drawFinder(0, size - 7);
    drawFinder(size - 7, 0);

    // Timing patterns
    for (let i = 8; i < size - 8; i++) {
      setFunctionModule(6, i, i % 2 === 0);
      setFunctionModule(i, 6, i % 2 === 0);
    }

    // Alignment patterns
    const centers = ALIGN_POSITIONS[version];
    for (let i = 0; i < centers.length; i++) {
      for (let j = 0; j < centers.length; j++) {
        const cy = centers[i];
        const cx = centers[j];
        if ((i === 0 && j === 0) || (i === 0 && j === centers.length - 1) || (i === centers.length - 1 && j === 0)) {
          continue;
        }
        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) {
            const dist = Math.max(Math.abs(dx), Math.abs(dy));
            setFunctionModule(cy + dy, cx + dx, dist !== 1);
          }
        }
      }
    }

    // Reserve format info areas
    for (let i = 0; i <= 8; i++) {
      if (i !== 6) {
        setFunctionModule(8, i, false);
        setFunctionModule(i, 8, false);
      }
    }
    for (let i = size - 8; i < size; i++) {
      setFunctionModule(8, i, false);
      setFunctionModule(i, 8, false);
    }
    setFunctionModule(size - 8, 8, true); // Dark module

    // Version info for V7+
    if (version >= 7) {
      let rem = version;
      for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      const bits = (version << 12) | rem;
      for (let i = 0; i < 18; i++) {
        const dark = ((bits >>> i) & 1) === 1;
        const a = size - 11 + (i % 3);
        const b = Math.floor(i / 3);
        setFunctionModule(a, b, dark);
        setFunctionModule(b, a, dark);
      }
    }

    return { size, modules, isFunction };
  }

  function placeDataBits(matrix, allCodewords) {
    const { size, modules, isFunction } = matrix;
    let bitIdx = 0;
    const totalBits = allCodewords.length * 8;

    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? size - 1 - vert : vert;
          if (!isFunction[y][x] && bitIdx < totalBits) {
            const byte = allCodewords[bitIdx >>> 3];
            const bit = (byte >>> (7 - (bitIdx & 7))) & 1;
            modules[y][x] = bit;
            bitIdx++;
          }
        }
      }
    }
  }

  function applyMaskAndFormat(matrix, mask) {
    const { size, modules, isFunction } = matrix;
    const masked = modules.map((row) => new Int8Array(row));

    for (let r = 0; r < size; r++) {
      for (let c = 0; c < size; c++) {
        if (isFunction[r][c]) continue;
        let invert = false;
        switch (mask) {
          case 0: invert = (r + c) % 2 === 0; break;
          case 1: invert = r % 2 === 0; break;
          case 2: invert = c % 3 === 0; break;
          case 3: invert = (r + c) % 3 === 0; break;
          case 4: invert = (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0; break;
          case 5: invert = ((r * c) % 2 + (r * c) % 3) === 0; break;
          case 6: invert = (((r * c) % 2 + (r * c) % 3) % 2) === 0; break;
          case 7: invert = (((r + c) % 2 + (r * c) % 3) % 2) === 0; break;
        }
        if (invert) masked[r][c] ^= 1;
      }
    }

    // Format info for ECC Level M (0b00) + mask
    const data = (0b00 << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const fmtBits = ((data << 10) | rem) ^ 0x5412;

    for (let i = 0; i <= 5; i++) masked[8][i] = (fmtBits >>> i) & 1;
    masked[8][7] = (fmtBits >>> 6) & 1;
    masked[8][8] = (fmtBits >>> 7) & 1;
    masked[7][8] = (fmtBits >>> 8) & 1;
    for (let i = 9; i < 15; i++) masked[14 - i][8] = (fmtBits >>> i) & 1;

    for (let i = 0; i < 8; i++) masked[size - 1 - i][8] = (fmtBits >>> i) & 1;
    for (let i = 8; i < 15; i++) masked[8][size - 15 + i] = (fmtBits >>> i) & 1;
    masked[size - 8][8] = 1;

    return masked;
  }

  function computePenalty(grid) {
    const n = grid.length;
    let penalty = 0;

    // Rule 1: adjacent modules in row/col
    for (let r = 0; r < n; r++) {
      let runColor = grid[r][0];
      let runLen = 1;
      for (let c = 1; c < n; c++) {
        if (grid[r][c] === runColor) {
          runLen++;
        } else {
          if (runLen >= 5) penalty += 3 + (runLen - 5);
          runColor = grid[r][c];
          runLen = 1;
        }
      }
      if (runLen >= 5) penalty += 3 + (runLen - 5);
    }

    for (let c = 0; c < n; c++) {
      let runColor = grid[0][c];
      let runLen = 1;
      for (let r = 1; r < n; r++) {
        if (grid[r][c] === runColor) {
          runLen++;
        } else {
          if (runLen >= 5) penalty += 3 + (runLen - 5);
          runColor = grid[r][c];
          runLen = 1;
        }
      }
      if (runLen >= 5) penalty += 3 + (runLen - 5);
    }

    // Rule 2: 2x2 blocks
    for (let r = 0; r < n - 1; r++) {
      for (let c = 0; c < n - 1; c++) {
        const v = grid[r][c];
        if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) {
          penalty += 3;
        }
      }
    }

    return penalty;
  }

  function generateQrMatrix(text) {
    const bytes = new TextEncoder().encode(text);
    let selectedVersion = -1;

    for (let v = 1; v <= 12; v++) {
      const headerBits = 4 + (v < 10 ? 8 : 16);
      const capacityBytes = Math.floor((VERSION_SPECS_M[v][0] * 8 - headerBits) / 8);
      if (bytes.length <= capacityBytes) {
        selectedVersion = v;
        break;
      }
    }

    if (selectedVersion === -1) {
      throw new Error('URL exceeds offline QR encoder capacity (max 287 bytes).');
    }

    const spec = VERSION_SPECS_M[selectedVersion];
    const dataCodewords = encodeDataBits(bytes, selectedVersion, spec[0]);
    const finalCodewords = interleaveBlocks(dataCodewords, spec);
    const matrix = createEmptyMatrix(selectedVersion);
    placeDataBits(matrix, finalCodewords);

    let bestGrid = null;
    let bestPenalty = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      const candidate = applyMaskAndFormat(matrix, mask);
      const p = computePenalty(candidate);
      if (p < bestPenalty) {
        bestPenalty = p;
        bestGrid = candidate;
      }
    }
    return bestGrid;
  }

  function renderQrToCanvas(canvas, text, options) {
    const opts = options || {};
    const sizePx = opts.size || 220;
    const quietModules = opts.quietModules || 4;
    const darkColor = opts.darkColor || '#0B0F19';
    const lightColor = opts.lightColor || '#FFFFFF';

    const grid = generateQrMatrix(text);
    const moduleCount = grid.length;
    const totalModules = moduleCount + quietModules * 2;
    const scale = Math.max(2, Math.floor(sizePx / totalModules));
    const finalSize = totalModules * scale;

    canvas.width = finalSize;
    canvas.height = finalSize;

    const ctx = canvas.getContext('2d');
    ctx.fillStyle = lightColor;
    ctx.fillRect(0, 0, finalSize, finalSize);
    ctx.fillStyle = darkColor;

    for (let r = 0; r < moduleCount; r++) {
      for (let c = 0; c < moduleCount; c++) {
        if (grid[r][c]) {
          ctx.fillRect((c + quietModules) * scale, (r + quietModules) * scale, scale, scale);
        }
      }
    }
  }

  global.OnionShieldQR = {
    generateMatrix: generateQrMatrix,
    renderToCanvas: renderQrToCanvas,
  };
})(typeof window !== 'undefined' ? window : globalThis);
