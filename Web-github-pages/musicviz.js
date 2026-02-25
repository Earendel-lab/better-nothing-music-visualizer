/**
 * musicViz.js - High-Performance Audio Analysis for Nothing Phone Glyphs
 * Optimized pipeline: mono → FFT → decay → zones → percent → brightness
 */
class MusicVisualizer {
    constructor(configPath, logCallback = null) {
        this.configPath = configPath;
        this.config = null;
        this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
        this.FPS = 60;
        this.logCallback = logCallback;
    }

    log(message) {
        if (this.logCallback) {
            this.logCallback(message);
        }
        console.log(message);
    }

    async loadConfig() {
        const response = await fetch(this.configPath);
        if (!response.ok) throw new Error(`Config load failed: ${response.status}`);
        try {
            const text = await response.text();
            this.config = JSON.parse(text);
        } catch (e) {
            throw new Error(`Failed to parse zones.config: ${e.message}`);
        }
    }

    getAvailablePhones() { 
        return Object.keys(this.config || {}).filter(k => k !== 'version' && k !== 'amp' && k !== 'decay-alpha' && k !== 'what-is-decay-alpha' && k !== 'what-is-decay');
    }
    getPhoneInfo(key) { 
        const phone = this.config?.[key];
        if (!phone) return null;
        return {
            phone_model: phone.phone_model || key,
            description: phone.description || '',
            zone_count: phone.zones?.length || 0,
            decay_alpha: this.config?.['decay-alpha'] || 0.8
        };
    }

    async loadAudioFile(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = async (e) => {
                try {
                    resolve(await this.audioContext.decodeAudioData(e.target.result));
                } catch (err) {
                    reject(new Error(`Decode failed: ${err.message}`));
                }
            };
            reader.onerror = () => reject(new Error('File read failed'));
            reader.readAsArrayBuffer(file);
        });
    }

    _extractUniqueFreqs(zones) {
        const freqs = new Set();
        for (const zone of zones) {
            if (Array.isArray(zone) && zone.length >= 2) {
                const low = parseFloat(zone[0]), high = parseFloat(zone[1]);
                if (!isNaN(low) && !isNaN(high)) {
                    freqs.add(JSON.stringify([Math.min(low, high), Math.max(low, high)]));
                }
            }
        }
        return Array.from(freqs).map(f => JSON.parse(f)).sort((a, b) => a[0] - b[0]);
    }

    _hannWindow(len) {
        const w = new Float32Array(len);
        for (let i = 0; i < len; i++) {
            w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (len - 1)));
        }
        return w;
    }

    _getMinMax(array) {
        let min = Infinity, max = -Infinity;
        for (let i = 0; i < array.length; i++) {
            if (array[i] < min) min = array[i];
            if (array[i] > max) max = array[i];
        }
        return { min: min === Infinity ? 0 : min, max: max === -Infinity ? 0 : max };
    }

    _applyDynamicMultipliers(freqTable, nFrames, nFreqs, ampConfig) {
        // Apply per-frame per-frequency dynamic multipliers for loudness normalization
        const ampMin = ampConfig.min || 0.5;
        const ampMax = ampConfig.max || 4.0;  // Note: Python uses 4.0, not 180!
        const target = ampConfig.target || 4000.0;
        const pct = ampConfig.percentile || 50.0;
        const smoothingAlpha = 0.3;

        this.log('[_applyDynamicMultipliers] Using percentile: ' + pct + '% target: ' + target + ' min: ' + ampMin + ' max: ' + ampMax);

        const multiplied = new Float32Array(freqTable.length);
        const baseMultipliers = [];

        // For each frequency, compute base multiplier from percentile
        for (let fi = 0; fi < nFreqs; fi++) {
            const freqValues = [];
            for (let i = 0; i < nFrames; i++) {
                freqValues.push(freqTable[i * nFreqs + fi]);
            }
            freqValues.sort((a, b) => a - b);
            
            const ref = freqValues[Math.floor(freqValues.length * pct / 100.0)];
            let baseMult = 1.0;
            if (ref > 1e-6) {
                baseMult = target / ref;
            }
            baseMult = Math.min(ampMax, Math.max(ampMin, baseMult));
            baseMultipliers.push(baseMult);
            
            this.log('[_applyDynamicMultipliers] Freq ' + fi + ': ref=' + ref.toFixed(4) + ' baseMult=' + baseMult.toFixed(2));

            // Compute per-frame dynamic multipliers with smoothing
            let prevMult = baseMult;
            for (let i = 0; i < nFrames; i++) {
                const currentValue = freqTable[i * nFreqs + fi];
                
                // Compute instantaneous multiplier for this frame
                let instMult;
                if (currentValue <= 1e-6) {
                    instMult = ampMax;
                } else {
                    instMult = target / currentValue;
                }
                
                // Clamp to limits
                instMult = Math.min(ampMax, Math.max(ampMin, instMult));
                
                // Smooth transition
                const smoothedMult = smoothingAlpha * instMult + (1.0 - smoothingAlpha) * prevMult;
                multiplied[i * nFreqs + fi] = currentValue * smoothedMult;
                prevMult = smoothedMult;
            }
        }

        return multiplied;
    }

    _goertzel(signal, sr, freqLow, freqHigh) {
        // Efficient Goertzel algorithm: targeted frequency analysis
        const freqMid = (freqLow + freqHigh) / 2;
        const omega = 2 * Math.PI * freqMid / sr;
        const coeff = 2 * Math.cos(omega);
        
        let s0 = 0, s1 = 0, s2 = 0;
        for (let i = 0; i < signal.length; i++) {
            s0 = signal[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        
        return Math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / signal.length;
    }

    _computeFrequencyTable(samples, sr, hop, winLen, window, uniqueFreqs, onProgress) {
        const nFrames = Math.ceil(samples.length / hop);
        const nFreqs = uniqueFreqs.length;
        const freqTable = new Float32Array(nFrames * nFreqs);
        const progressStep = Math.max(1, Math.floor(nFrames / 100));

        for (let i = 0; i < nFrames; i++) {
            const start = i * hop;
            const frame = new Float32Array(winLen);

            // Extract and window frame
            for (let j = 0; j < winLen; j++) {
                frame[j] = (start + j < samples.length ? samples[start + j] : 0) * window[j];
            }

            // Analyze each frequency range using Goertzel
            for (let fi = 0; fi < nFreqs; fi++) {
                const [low, high] = uniqueFreqs[fi];
                freqTable[i * nFreqs + fi] = this._goertzel(frame, sr, low, high);
            }

            if ((i + 1) % progressStep === 0 || i === nFrames - 1) {
                if (onProgress) {
                    onProgress({
                        stage: 'FFT Analysis',
                        progress: Math.round((i + 1) / nFrames * 100),
                        current: i + 1,
                        total: nFrames
                    });
                }
            }
        }

        return { freqTable, nFrames, nFreqs };
    }

    _applyDecay(freqTable, decayAlpha, nFrames, nFreqs) {
        // Instant rise, smooth exponential decay
        const alpha = 0.86 + decayAlpha / 10;
        const decayed = new Float32Array(freqTable.length);

        if (nFrames === 0) return decayed;

        // First frame
        for (let fi = 0; fi < nFreqs; fi++) {
            decayed[fi] = freqTable[fi];
        }

        // Decay: instant rise (max), smooth fall
        for (let i = 1; i < nFrames; i++) {
            for (let fi = 0; fi < nFreqs; fi++) {
                const idx = i * nFreqs + fi;
                const prevIdx = (i - 1) * nFreqs + fi;
                const prev = decayed[prevIdx];
                const cur = freqTable[idx];
                const risen = Math.max(prev, cur);
                decayed[idx] = alpha * risen + (1 - alpha) * cur;
            }
        }

        return decayed;
    }

    _mapToZones(freqTable, uniqueFreqs, zones, nFrames, nFreqs) {
        // Map frequencies to zones with quadratic normalization
        const nZones = zones.length;
        const zoneTable = new Float32Array(nFrames * nZones);
        let maxZoneVal = 0;

        for (let i = 0; i < nFrames; i++) {
            for (let zi = 0; zi < nZones; zi++) {
                const zone = zones[zi];
                const zoneLow = parseFloat(zone[0]);
                const zoneHigh = parseFloat(zone[1]);
                let maxVal = 0;

                // Find max frequency in this zone's range
                for (let fi = 0; fi < nFreqs; fi++) {
                    const [fLow, fHigh] = uniqueFreqs[fi];
                    // Check overlap
                    if (!(fHigh < zoneLow || fLow > zoneHigh)) {
                        maxVal = Math.max(maxVal, freqTable[i * nFreqs + fi]);
                    }
                }

                // Quadratic normalization
                const quadratic = maxVal * maxVal;
                zoneTable[i * nZones + zi] = quadratic;
                maxZoneVal = Math.max(maxZoneVal, quadratic);
            }
        }

        this.log('[_mapToZones] Max zone value after quadratic: ' + maxZoneVal);
        return zoneTable;
    }

    _scaleToBrightness(zoneTable, nFrames, nZones) {
        // Scale to 0-4095 brightness range using quadratic correction
        const scaled = new Float32Array(zoneTable.length);
        let maxVal = 0;
        
        // Find max value for scaling
        for (let i = 0; i < zoneTable.length; i++) {
            maxVal = Math.max(maxVal, zoneTable[i]);
        }
        
        this.log('[_scaleToBrightness] Max input value: ' + maxVal);
        
        // Scale and apply quadratic correction
        const scale = maxVal > 0 ? 4095 / maxVal : 1;
        for (let i = 0; i < zoneTable.length; i++) {
            const normalized = zoneTable[i] * scale / 4095; // 0-1 range
            scaled[i] = normalized * normalized * 4095; // quadratic correction, back to 0-4095
        }
        
        const scaledMinMax = this._getMinMax(scaled);
        this.log('[_scaleToBrightness] Scale factor: ' + scale + ' Scaled max: ' + scaledMinMax.max);
        return scaled;
    }

    _applyPercentMapping(zoneTable, zones, nFrames) {
        // Map to percentage range [low, high]
        const nZones = zones.length;
        const mapped = new Float32Array(zoneTable.length);
        const maxLinear = 5000.0;

        for (let i = 0; i < nFrames; i++) {
            for (let zi = 0; zi < nZones; zi++) {
                const idx = i * nZones + zi;
                const val = Math.min(1.0, Math.max(0.0, zoneTable[idx] / maxLinear));
                const zone = zones[zi];
                const low = zone.length > 3 ? parseFloat(zone[3]) : 0;
                const high = zone.length > 4 ? parseFloat(zone[4]) : 100;
                mapped[idx] = Math.round(low + val * (high - low));
            }
        }

        return mapped;
    }

    _clipToBrightness(values) {
        // Clip to 0-4095 brightness range
        const clipped = new Float32Array(values.length);
        for (let i = 0; i < values.length; i++) {
            clipped[i] = Math.round(Math.max(0, Math.min(4095, values[i])));
        }
        return clipped;
    }

    async processAudio(audioFile, phoneKey, onProgress = null) {
        if (!this.config) await this.loadConfig();

        const conf = this.config[phoneKey];
        if (!conf || !conf.zones) throw new Error(`Phone ${phoneKey} not found or has no zones`);

        this.log('[processAudio] Starting audio processing for ' + audioFile.name + ' phone: ' + phoneKey);

        // Load and decode to mono
        const audioBuffer = await this.loadAudioFile(audioFile);
        const samples = audioBuffer.getChannelData(0);
        const sr = audioBuffer.sampleRate;

        this.log('[processAudio] Audio loaded: ' + samples.length + ' samples at ' + sr + ' Hz');

        // Extract unique frequencies from zones
        const uniqueFreqs = this._extractUniqueFreqs(conf.zones);
        this.log('[processAudio] Extracted ' + uniqueFreqs.length + ' unique frequency ranges');

        // FFT parameters
        const hop = Math.round(sr / this.FPS);
        const winLen = Math.round(sr * 0.025);
        const window = this._hannWindow(winLen);

        this.log('[processAudio] FFT params - hop: ' + hop + ' winLen: ' + winLen + ' FPS: ' + this.FPS);

        // Frequency analysis
        const { freqTable, nFrames, nFreqs } = this._computeFrequencyTable(
            samples, sr, hop, winLen, window, uniqueFreqs, onProgress
        );

        this.log('[processAudio] FFT complete: ' + nFrames + ' frames, ' + nFreqs + ' frequencies');
        const freqMinMax = this._getMinMax(freqTable);
        this.log('[processAudio] FreqTable range: [' + freqMinMax.min.toFixed(6) + ', ' + freqMinMax.max.toFixed(6) + ']');

        // Get decay alpha and amp config from global config
        const decayAlpha = this.config['decay-alpha'] || 0.8;
        const ampConfig = this.config.amp || { min: 0.5, max: 180.0 };

        this.log('[processAudio] Amp config: ' + JSON.stringify(ampConfig));

        // Pipeline: multiply → decay → zones → scale → clip to brightness
        const multiplied = this._applyDynamicMultipliers(freqTable, nFrames, nFreqs, ampConfig);
        const multMinMax = this._getMinMax(multiplied);
        this.log('[processAudio] After multipliers: [' + multMinMax.min.toFixed(2) + ', ' + multMinMax.max.toFixed(2) + ']');

        const decayed = this._applyDecay(multiplied, decayAlpha, nFrames, nFreqs);
        const decayMinMax = this._getMinMax(decayed);
        this.log('[processAudio] After decay: [' + decayMinMax.min.toFixed(2) + ', ' + decayMinMax.max.toFixed(2) + ']');

        const zones = this._mapToZones(decayed, uniqueFreqs, conf.zones, nFrames, nFreqs);
        const zoneMinMax = this._getMinMax(zones);
        this.log('[processAudio] After zone mapping: [' + zoneMinMax.min.toFixed(2) + ', ' + zoneMinMax.max.toFixed(2) + ']');

        const scaled = this._scaleToBrightness(zones, nFrames, conf.zones.length);
        const scaledMinMax = this._getMinMax(scaled);
        this.log('[processAudio] After brightness scaling: [' + scaledMinMax.min.toFixed(2) + ', ' + scaledMinMax.max.toFixed(2) + ']');

        const final = this._clipToBrightness(scaled);
        const finalMinMax = this._getMinMax(final);
        this.log('[processAudio] After clipping: [' + finalMinMax.min + ', ' + finalMinMax.max + ']');

        return {
            data: final,
            nFrames,
            nZones: conf.zones.length,
            phoneModel: conf.phone_model,
            phoneKey,
            fileName: audioFile.name,
            nFreqs
        };
    }

    exportAsNGlyph(result) {
        const { data, nFrames, nZones, phoneModel } = result;
        const authorRows = [];
    
        for (let i = 0; i < nFrames; i++) {
            let row = '';
            for (let zi = 0; zi < nZones; zi++) {
                row += Math.round(data[i * nZones + zi]) + ',';
            }
            authorRows.push(row); // push string per frame
        }
    
        const obj = {
            VERSION: 1,
            PHONE_MODEL: phoneModel,
            AUTHOR: authorRows,
            CUSTOM1: ["1-0", "1050-1"]
        };
    
        return JSON.stringify(obj, null, 4);
    }

    exportAsCSV(result) {
        const { data, nFrames, nZones } = result;
        const rows = [];
        for (let i = 0; i < nFrames; i++) {
            const row = [];
            for (let zi = 0; zi < nZones; zi++) {
                row.push(data[i * nZones + zi]);
            }
            rows.push(row.join(','));
        }
        return rows.join('\n');
    }

    exportAsCompact(result) {
        const { data, nFrames, nZones } = result;
        const buffer = new ArrayBuffer(4 + 4 + data.length * 2);
        const view = new DataView(buffer);
        view.setUint32(0, nFrames, true);
        view.setUint32(4, nZones, true);
        
        for (let i = 0; i < data.length; i++) {
            view.setUint16(8 + i * 2, data[i], true);
        }
        return buffer;
    }
}
