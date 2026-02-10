#!/usr/bin/env python3
"""
musicViz.py - a tool that brings better music visualization to Nothing Phones!
https://github.com/Aleks-Levet/better-nothing-music-visualizer

Copyright (C) 2024  Aleks Levet and SebiAi.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""

# VERSION 1.0.0 OF THE SCRIPT.

import os, sys, json, subprocess
import numpy as np
from scipy.fft import rfft, rfftfreq
from scipy.signal import get_window
import urllib.request
import urllib.error
import time
import tempfile
import shutil
import soundfile as sf
from typing import Optional, Tuple  # added typing

# Editable ffmpeg conversion settings (tweak these as needed)
CONVERT_SETTINGS = {
    "ffmpeg_bin": "ffmpeg",  # path to ffmpeg binary
    "quality": 7,            # -q:a for libvorbis (0..10), ignored for libopus
    "bitrate": None,         # e.g. "128k" (used for libopus or if you prefer bitrate over quality)
    "sample_rate": None,     # e.g. 48000 or None to keep source
    "channels": None,        # e.g. 2 or 1 or None to keep source
    "extra_args": []         # additional ffmpeg args list, e.g. ["-vn", "-map_metadata", "-1"]
}

# ------------------ helpers ------------------
def convert_to_ogg(input_path, output_path):
    """
    Convert any audio file to OGG using ffmpeg with settings from CONVERT_SETTINGS.
    The function signature is unchanged so existing callers still work.
    """
    ffmpeg = CONVERT_SETTINGS.get("ffmpeg_bin", "ffmpeg")
    quality = CONVERT_SETTINGS.get("quality")
    bitrate = CONVERT_SETTINGS.get("bitrate")
    sr = CONVERT_SETTINGS.get("sample_rate")
    channels = CONVERT_SETTINGS.get("channels")
    extra = CONVERT_SETTINGS.get("extra_args", []) or []

    # -vn: strip video streams (album art/covers that break Nothing Composer)
    # -map_metadata -1: strip source metadata to avoid conflicts
    cmd = [ffmpeg, "-y", "-i", input_path, "-vn", "-map_metadata", "-1"]

    if sr is not None:
        cmd += ["-ar", str(int(sr))]
    if channels is not None:
        cmd += ["-ac", str(int(channels))]

    cmd += ["-c:a", "libopus"]
    if bitrate:
        cmd += ["-b:a", str(bitrate)]
    elif quality is not None:
        # map quality to bitrate fallback if desired; default to 96k..192k mapping could be added
        cmd += ["-b:a", f"{int(quality)*16}k"]
  
    # append any user-specified extra ffmpeg args
    cmd += list(extra)
    cmd += [output_path]
    print(f"[+] Converting with ffmpeg...")
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(res.stdout)
        print(res.stderr)
        raise RuntimeError(f"ffmpeg conversion failed for {input_path}")
    print(f"[+] Conversion complete!")
    return output_path

def load_audio_mono(path):
    """
    Load audio using soundfile and return (mono_samples, sr).
    Falls back to ffmpeg->wav if soundfile cannot read the source format.
    Samples are float32 in [-1, 1].
    """
    try:
        data, sr = sf.read(path, dtype='float32', always_2d=False)
    except Exception:
        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".wav")
        tmp.close()
        try:
            subprocess.run(
                ["ffmpeg", "-y", "-i", path, tmp.name],
                check=True, capture_output=True, text=True
            )
            data, sr = sf.read(tmp.name, dtype='float32', always_2d=False)
        finally:
            try:
                os.unlink(tmp.name)
            except Exception:
                pass

    # Ensure mono
    if data.ndim == 2 and data.shape[1] > 1:
        samples = data.mean(axis=1).astype(np.float32)
    else:
        samples = data.flatten().astype(np.float32)
    return samples, sr

def next_pow2(n):
    p = 1
    while p < n:
        p <<= 1
    return p

def compute_zone_peak(mag, freqs, low, high):
    idx = np.where((freqs >= low) & (freqs <= high))[0]
    return float(np.max(mag[idx])) if idx.size else 0.0

# new helper: compute raw per-frame zone peaks (simpler, with light progress)
def compute_raw_matrix(samples, sr, zones, fps):
    hop = int(round(sr / float(fps)))
    win_len = int(round(sr * 0.025))
    win = get_window("hann", win_len, fftbins=True)
    nfft = next_pow2(win_len)
    freqs = rfftfreq(nfft, 1 / sr)
    n_frames = int(np.ceil(len(samples) / hop))
    raw = np.zeros((n_frames, len(zones)), dtype=float)

    # light progress: cooldown ticks
    tick = max(1, n_frames // 500)

    for i in range(n_frames):
        start = i * hop
        frame = samples[start:start+win_len]
        if frame.size < win_len:
            pad_width: Tuple[int, int] = (0, int(win_len - int(frame.size)))
            frame = np.pad(frame, pad_width)
        spec = np.abs(rfft(frame * win, n=nfft))

        # accept zone entries like [low, high] or [low, high, "description"]
        for zi, zone in enumerate(zones):
            # robust handling: ensure we have at least two numeric bounds
            if not (isinstance(zone, (list, tuple)) and len(zone) >= 2):
                print(f"[!] Invalid zone entry at index {zi}: {zone!r} -- using 0..0")
                low = 0.0
                high = 0.0
            else:
                try:
                    low = float(zone[0])
                    high = float(zone[1])
                except Exception:
                    print(f"[!] Invalid numeric bounds for zone {zi}: {zone!r} -- using 0..0")
                    low = 0.0
                    high = 0.0

            # swap if bounds reversed (user-supplied reversed ranges were producing empty values)
            if low > high:
                low, high = high, low
                print(f"[!] Warning: swapped zone bounds for zone {zi} -> low={low}, high={high}")

            if low == 0 and high == 0:
                raw[i, zi] = 0.0
            else:
                raw[i, zi] = compute_zone_peak(spec, freqs, low, high)

        if (i + 1) % tick == 0 or i == n_frames - 1:
            pct = int((i + 1) / n_frames * 100)
            print(f"\r[FFT] Analysing frequencies: {pct}% ({i+1}/{n_frames})", end='', flush=True)
    print()  # newline after progress
    return raw, n_frames

# normalize raw (0..1) to quadratic brightness (0..5000)
def normalize_to_quadratic(raw):
    zone_max = np.max(raw, axis=0)
    zone_max[zone_max == 0] = 1e-12
    scaled = raw / zone_max
    # quadratic mapping for emphasis on peaks
    return (scaled ** 2 * 5000.0).astype(float)

# simple stable multiplier: use median of frame maxima
def compute_stable_multiplier(linear, amp_conf):
    if linear.size == 0:
        return 1.0
    frame_maxes = np.max(linear, axis=1)
    pct = float(amp_conf.get("percentile", 50.0))  # default median
    ref = float(np.percentile(frame_maxes, pct))
    target = float(amp_conf.get("target", 3000.0))
    if ref <= 0.0:
        mult = 1.0
    else:
        mult = target / ref
    amp_min = float(amp_conf.get("min"))
    amp_max = float(amp_conf.get("max"))
    return float(max(amp_min, min(amp_max, mult)))

# Apply decay to raw FFT values (instant rise, smoothed fall)
def apply_decay_to_raw(raw, decay_alpha):
    n_frames, n_zones = raw.shape
    decayed = np.zeros_like(raw)
    if n_frames == 0:
        return decayed
    prev = raw[0].copy()
    decayed[0] = prev
    for i in range(1, n_frames):
        cur = raw[i]
        # instant rise
        prev = np.maximum(prev, cur)
        # smoothed fall
        prev = decay_alpha * prev + (1.0 - decay_alpha) * cur
        decayed[i] = prev
    return decayed

# Apply stable multiplier without smoothing
def apply_multiplier_only(linear, amp_conf):
    n_frames, n_zones = linear.shape
    mult = compute_stable_multiplier(linear, amp_conf)
    linear_scaled = linear * mult
    return linear_scaled  # return float, no clip yet

# new helper: map per-zone brightness using optional zone[3]=low_percent and zone[4]=high_percent
def apply_zone_percent_mapping(linear: np.ndarray, zones, linear_max: float = 5000.0) -> np.ndarray:
    """
    Apply per-zone percent mapping on the pre-smoothed 'linear' matrix.
    - linear: (n_frames, n_zones) float array in range ~0..linear_max (default 5000)
    - zones: list of zone entries where zone[3]=low_percent, zone[4]=high_percent (optional)

    Behaviour:
      perc = (linear_value / linear_max) * 100
      if perc <= low -> mapped_linear = 0
      if perc >= high -> mapped_linear = linear_max
      else -> mapped_linear = ((perc - low)/(high - low)) * linear_max

    Returns mapped linear array in the same scale as 'linear' (float).
    """
    if linear.size == 0:
        return linear
    src = linear.astype(np.float64)
    out = src.copy()
    n_frames, n_zones = out.shape

    def _parse_percent(v):
        try:
            if isinstance(v, str):
                s = v.strip()
                if s.endswith('%'):
                    s = s[:-1].strip()
                val = float(s)
            else:
                val = float(v)
        except Exception:
            return None
        if 0.0 <= val <= 1.0:
            return val * 100.0
        return val

    for zi, zone in enumerate(zones):
        if not (isinstance(zone, (list, tuple)) and len(zone) >= 5):
            continue
        low = _parse_percent(zone[3])
        high = _parse_percent(zone[4])
        if low is None or high is None:
            continue
        low = max(0.0, min(100.0, low))
        high = max(0.0, min(100.0, high))
        if low > high:
            low, high = high, low

        percents = (src[:, zi] / float(linear_max)) * 100.0
        if high == low:
            mask_hi = percents >= high
            out[:, zi] = 0.0
            out[mask_hi, zi] = float(linear_max)
            continue

        below = percents <= low
        above = percents >= high
        between = (~below) & (~above)

        out[below, zi] = 0.0
        out[above, zi] = float(linear_max)
        if np.any(between):
            out[between, zi] = ((percents[between] - low) / (high - low)) * float(linear_max)
    print("[+] Processed progressbar zone percent mapping.")
    return out

# ------------------ main processing ------------------
def process(audio_path, conf, out_path, output_format='nglyph'):
    # --- config
     fps = 60  # FPS is fixed to 60 
     phone_model = conf.get("phone_model")
     decay_alpha = conf.get("decay_alpha")
     zones = conf["zones"]
     amp_conf = conf.get("amp")
 
     # --- audio analysis
     samples, sr = load_audio_mono(audio_path)
 
     # compute raw matrix
     raw, n_frames = compute_raw_matrix(samples, sr, zones, fps)
 
     # normalize to quadratic linear 0..5000
     linear = normalize_to_quadratic(raw)
 
     # apply stable multiplier
     linear_scaled = apply_multiplier_only(linear, amp_conf)
 
    # apply per-zone percent mapping (optional zone[3], zone[4]) on scaled values
     try:
         linear_scaled = apply_zone_percent_mapping(linear_scaled, zones, linear_max=4095.0)
     except Exception as e:
         print(f"[!] Warning: zone percent mapping failed: {e}")
  
     # apply decay to scaled brightness values
     decayed_linear = apply_decay_to_raw(linear_scaled, decay_alpha)
 
     # clip to 0-4095
     final = np.clip(np.round(decayed_linear), 0, 4095).astype(int)
 
     # --- write output
     if output_format == 'nglyph':
         author_rows = [",".join(map(str, row)) + "," for row in final]
         ng = {
             "VERSION": 1,
             "PHONE_MODEL": phone_model,
             "AUTHOR": author_rows,
             "CUSTOM1": ["1-0", "1050-1"]
         }
         with open(out_path, "w", encoding="utf-8") as f:
             json.dump(ng, f, indent=4)
         print(f"[+] Saved NGlyph: {out_path}")
     elif output_format == 'csv':
         import csv
         with open(out_path, "w", newline='', encoding="utf-8") as f:
             writer = csv.writer(f)
             # Write phone model
             writer.writerow([f"PHONE_MODEL: {phone_model}"])
             # Write header
             header = [f"zone_{i}" for i in range(final.shape[1])]
             writer.writerow(header)
             # Write rows
             for row in final:
                 writer.writerow(row)
         print(f"[+] Saved CSV: {out_path}")
     elif output_format == 'compact':
         # .musicviz binary format:
         # - Text header: "PHONE_MODEL: <model>\n"
         # - Binary: uint32 n_frames, uint32 n_zones
         # - Binary: n_frames * n_zones uint16 brightness values (0-4095)
         # Very space efficient for storage.
         with open(out_path, "wb") as f:
             # Write header as text
             header = f"PHONE_MODEL: {phone_model}\n"
             f.write(header.encode('utf-8'))
             # Write dimensions and binary data
             n_frames, n_zones = final.shape
             import struct
             f.write(struct.pack('II', n_frames, n_zones))
             final_bytes = final.astype(np.uint16).tobytes()
             f.write(final_bytes)
         print(f"[+] Saved Compact: {out_path}")
     return out_path

def run_glyphmodder_write(nglyph_path: str, ogg_path: str, title: Optional[str] = None, cwd: Optional[str] = None) -> str:
    if not isinstance(ogg_path, str) or not ogg_path:
        raise ValueError("ogg_path must be a non-empty string")
    if title is None:
        title = os.path.splitext(os.path.basename(nglyph_path))[0]
    # locate GlyphModder.py in the current working directory
    glyphmodder_path = os.path.normpath(os.path.join(os.getcwd(), "GlyphModder.py"))
    if not os.path.isfile(glyphmodder_path):
        print(f"GlyphModder.py not found in working directory. Searched: {glyphmodder_path}")
        print("Downloading GlyphModder.py from SebiAI's GitHub repository...")
        download_glyphmodder_to_cwd(overwrite=True)
        print(f"[+] Proceeding with the downloaded GlyphModder.py from cwd.")
        
    # ensure NGlyph is an absolute path (so GlyphModder can find it from any cwd)
    arg_nglyph = os.path.abspath(nglyph_path)

    # if we run with cwd set to the output dir, pass only the basename for the ogg
    arg_ogg = os.path.basename(ogg_path) if cwd else ogg_path
    if not isinstance(arg_ogg, str) or not arg_ogg:
        raise ValueError("Computed arg_ogg is not a valid string")

    cmd = [sys.executable, glyphmodder_path, "write", "--auto-fix-audio", "-t", title, arg_nglyph, arg_ogg]
    #print("[+] Running GlyphModder:", " ".join(cmd), f"(cwd={cwd or os.getcwd()})")
    print("[+] Running GlyphModder...")
    res = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)
    if res.returncode != 0:
        print(res.stdout)
        print(res.stderr)
        raise RuntimeError("GlyphModder failed")
    # final ogg should be written into cwd (if provided)
    final_ogg_path = os.path.join(cwd or os.getcwd(), arg_ogg) if cwd else os.path.abspath(arg_ogg)
    #print(f"[+] GlyphModder produced: {final_ogg_path}")
    print(f"[+] GlyphModder done!")
    return final_ogg_path

# new helper: ensure GlyphModder.py exists in current directory (download from SebiAI if needed)
def download_glyphmodder_to_cwd(overwrite: bool = False, attempts: int = 2, backoff: float = 1.0) -> bool:
    """
    Try to download GlyphModder.py from SebiAI's GitHub raw URLs into cwd.
    If overwrite is False and a file already exists, do nothing.
    Returns True on success (file now exists in cwd), False otherwise.
    """
    target = os.path.join(os.getcwd(), "GlyphModder.py")
    if os.path.isfile(target) and not overwrite:
        print(f"[+] GlyphModder.py already present in cwd: {target}")
        return True

    url = "https://raw.githubusercontent.com/SebiAi/custom-nothing-glyph-tools/main/GlyphModder.py"
    last_err = None
    for attempt in range(1, attempts + 1):
        try:
            print(f"[+] Downloading GlyphModder from SebiAI's repo (attempt {attempt}) ...")
            with urllib.request.urlopen(url, timeout=10) as resp:
                if resp.status != 200:
                    raise urllib.error.HTTPError(url, resp.status, "Non-200 response", resp.headers, None)
                data = resp.read()
            # write atomically
            tmp = target + ".tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, target)
            print(f"[+] Saved GlyphModder.py -> {target}")
            return True
        except Exception as e:
            last_err = e
            print(f"[!] Download attempt {attempt} failed: {e}")
            time.sleep(backoff * attempt)
    print(f"[!] Failed to download from github: {last_err}")
    print("[!] Could not obtain GlyphModder.py from SebiAI's GitHub.")
    return False

# new helper: generate a short help message from zones.config
def generate_help_from_zones(cfg_path="zones.config"):
    if not os.path.isfile(cfg_path):
        return (f"{cfg_path} not found in working directory.\n"
                "Download it from the repository.")
    try:
        raw = json.load(open(cfg_path, "r", encoding="utf-8"))
    except Exception as e:
        return f"Failed to read {cfg_path}: {e}"
    
    # only include entries that look like phone configs (dicts).  This filters out metadata like decay-alpha.
    conf_map = {k: v for k, v in raw.items() if k != "amp" and isinstance(v, dict)}
    lines = []
    lines.append("Usage: python musicViz.py [--update] [--nglyph] [--csv] [--compact] [--np1|--np1s|--np2|--np2a|--np3a]\n")
    lines.append(f"Available configs (from {cfg_path}):")
    for key, cfg in conf_map.items():
        pm = cfg.get("phone_model", "<unknown>")
        desc = cfg.get("description", "")
        zones = cfg.get("zones", []) or []      
        lines.append(f"  --{key}: {pm} - {desc} ({len(zones)} zones)")
    lines.append("\nExamples:")
    lines.append("  python musicViz.py --np1          # use np1 config")
    lines.append("  python musicViz.py --np1 --nglyph  # only generate an nglyph file using np1 config")
    lines.append("  python musicViz.py --np1 --csv     # only generate a csv file with zone brightnesses using np1 config")
    lines.append("  python musicViz.py --np1 --compact # only generate a compact .musicviz file with zone brightnesses using np1 config (binary format)")
    lines.append("  python musicViz.py --np1s --update  # update GlyphModder.py and run")
    return "\n".join(lines)

# new helper: validate amp configuration 
def validate_amp_conf(amp_conf):
    if not isinstance(amp_conf, dict):
        raise ValueError("The 'amp' entry must be an object in zones.config (global) or inside a phone config.")
    # require at least min and max to be present
    for key in ("min", "max"):
        if key not in amp_conf:
            raise ValueError(f"amp missing required field '{key}' — please add it to zones.config")
    # Only coerce known numeric keys. Ignore other keys (e.g. description).
    numeric_keys = ("min", "max", "initial", "up_speed", "down_speed", "percentile", "target")
    coerced = {}
    for k in numeric_keys:
        if k in amp_conf:
            v = amp_conf[k]
            try:
                coerced[k] = float(v)
            except Exception:
                raise ValueError(f"amp.{k} must be numeric (got {repr(v)})")
    return coerced


# ---------------- api ------------------
# Relies on the standard pipeline: compute_raw_matrix -> normalize_to_quadratic -> apply_multiplier_only -> apply_zone_percent_mapping -> apply_decay_to_raw -> clip

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

class GlyphVisualizerAPI:
    """
    Simplified API for generating Nothing Phone glyph visualizer OGG files.
    Uses the main processing pipeline from this module.
    """

    def __init__(self, zones_config_path: str = None):
        if zones_config_path is None:
            zones_config_path = os.path.join(_SCRIPT_DIR, "zones.config")
        if not os.path.isfile(zones_config_path):
            raise FileNotFoundError(f"zones.config not found at: {zones_config_path}")
        self.zones_config_path = zones_config_path
        self._load_config()

    def _load_config(self):
        with open(self.zones_config_path, "r", encoding="utf-8") as f:
            self.raw_config = json.load(f)
        self.global_amp = self.raw_config.get("amp")
        if self.global_amp is None:
            raise ValueError("zones.config must include a top-level 'amp' object")
        raw_decay = self.raw_config.get("decay-alpha") or self.raw_config.get("decay_alpha")
        self.global_decay = float(raw_decay) if raw_decay is not None else None
        self.phone_configs = {
            k: v for k, v in self.raw_config.items()
            if k != "amp" and isinstance(v, dict)
        }

    def get_available_phones(self) -> list:
        return list(self.phone_configs.keys())

    def get_phone_info(self, phone_key: str) -> dict:
        if phone_key not in self.phone_configs:
            raise ValueError(f"Unknown phone key: {phone_key}")
        conf = self.phone_configs[phone_key]
        return {
            "key": phone_key,
            "description": conf.get("description", ""),
            "phone_model": conf.get("phone_model", "UNKNOWN"),
            "zone_count": len(conf.get("zones", []))
        }

    def _prepare_config(self, phone_key: str) -> dict:
        if phone_key not in self.phone_configs:
            raise ValueError(f"Unknown phone key: {phone_key}")
        conf = dict(self.phone_configs[phone_key])
        amp_conf = conf.get("amp") if conf.get("amp") is not None else self.global_amp
        if amp_conf is None:
            raise ValueError("Missing 'amp' configuration")
        conf["amp"] = validate_amp_conf(amp_conf)
        if "decay-alpha" in conf:
            conf["decay_alpha"] = float(conf["decay-alpha"])
        elif "decay_alpha" in conf:
            conf["decay_alpha"] = float(conf["decay_alpha"])
        elif self.global_decay is not None:
            conf["decay_alpha"] = self.global_decay
        else:
            raise ValueError("Missing 'decay_alpha' configuration")
        if "zones" not in conf or not isinstance(conf["zones"], list):
            raise ValueError("Configuration missing 'zones' array")
        return conf

    def _process_audio(self, audio_path: str, conf: dict, out_path: str, output_format: str = 'nglyph') -> str:
        """
        Process audio using the standard pipeline and write .nglyph.
        This function avoids extra per-frame Python loops for postprocessing.
        """
        fps = 60
        phone_model = conf.get("phone_model")
        decay_alpha = conf.get("decay_alpha")
        zones = conf["zones"]
        amp_conf = conf.get("amp")

        samples, sr = load_audio_mono(audio_path)
        raw, n_frames = compute_raw_matrix(samples, sr, zones, fps)

        # Vectorized normalization + multiplier + decay pipeline
        linear = normalize_to_quadratic(raw)  # float matrix
        linear_scaled = apply_multiplier_only(linear, amp_conf)
        try:
            linear_scaled = apply_zone_percent_mapping(linear_scaled, zones, linear_max=4095.0)
        except Exception as e:
            print(f"[!] Warning: zone percent mapping failed in API: {e}")
        decayed_linear = apply_decay_to_raw(linear_scaled, decay_alpha)
        final = np.clip(np.round(decayed_linear), 0, 4095).astype(int)

        # --- write NGlyph
        author_rows = [",".join(map(str, row)) + "," for row in final]
        ng = {
            "VERSION": 1,
            "PHONE_MODEL": phone_model,
            "AUTHOR": author_rows,
            "CUSTOM1": ["1-0", "1050-1"]
        }
        with open(out_nglyph_path, "w", encoding="utf-8") as f:
            json.dump(ng, f, indent=4)
        return out_nglyph_path

    def generate_glyph_ogg(
        self,
        audio_path: str,
        phone_model: str = "np1",
        output_path: str = None,
        title: str = None,
        nglyph_only: bool = False
    ) -> str:
        """
        Generate glyph OGG (or .nglyph if nglyph_only=True).
        Simpler call signature and faster internals than the previous API.
        """
        if not os.path.isfile(audio_path):
            raise FileNotFoundError(f"Audio file not found: {audio_path}")
        conf = self._prepare_config(phone_model)
        base_name = os.path.splitext(os.path.basename(audio_path))[0]
        if title is None:
            title = base_name

        work_dir = tempfile.mkdtemp(prefix="glyph_viz_")
        try:
            nglyph_path = os.path.join(work_dir, f"{base_name}.nglyph")
            self._process_audio(audio_path, conf, nglyph_path)

            if nglyph_only:
                if output_path is None:
                    output_path = os.path.join(os.path.dirname(audio_path), f"{base_name}.nglyph")
                shutil.copy2(nglyph_path, output_path)
                return output_path

            ogg_path = os.path.join(work_dir, f"{base_name}.ogg")
            convert_to_ogg(audio_path, ogg_path)

            # Ensure GlyphModder is available in work_dir
            original_cwd = os.getcwd()
            os.chdir(work_dir)
            try:
                download_glyphmodder_to_cwd(overwrite=False)
            finally:
                os.chdir(original_cwd)

            run_glyphmodder_write(nglyph_path, ogg_path, title=title, cwd=work_dir)

            # Find produced file and copy to output_path
            composed_patterns = [
                os.path.join(work_dir, f"{base_name}_fixed_composed.ogg"),
                os.path.join(work_dir, f"{base_name}_composed.ogg"),
            ]
            composed_file = next((p for p in composed_patterns if os.path.isfile(p)), None)
            if not composed_file:
                raise RuntimeError(f"GlyphModder did not produce expected output. Check work_dir: {work_dir}")

            if output_path is None:
                output_path = os.path.join(os.path.dirname(audio_path), f"{base_name}_glyph.ogg")
            shutil.copy2(composed_file, output_path)
            return output_path
        finally:
            try:
                shutil.rmtree(work_dir)
            except Exception:
                pass

# Convenience wrapper (keeps previous simple-call behavior)
def generate_glyph_ogg(
    audio_path: str,
    phone_model: str = "np1",
    output_path: str = None,
    title: str = None
) -> str:
    api = GlyphVisualizerAPI()
    return api.generate_glyph_ogg(audio_path, phone_model, output_path, title)
#-------------------api end-------------------




# ------------------ entrypoint -----------------+-+-+-+-+-+-+-+-+-*-*-*-*-*-*-*-*-/*/*/*/*/*///
if __name__ == "__main__":
    # if script called with no args, show help generated from zones.config
    if len(sys.argv) == 1:
        cfg_path_hint = "zones.config"
        print(generate_help_from_zones(cfg_path_hint))
        sys.exit(0)

    # accept optional --update flag to force overwriting GlyphModder.py from GitHub
    update_flag = False
    if "--update" in sys.argv:
        update_flag = True
        # remove flag so it doesn't interfere with other argument handling
        sys.argv = [a for a in sys.argv if a != "--update"]

    # accept --nglyph flag: only produce .nglyph files, skip conversion and GlyphModder
    nglyph_only = False
    if "--nglyph" in sys.argv:
        nglyph_only = True
        sys.argv = [a for a in sys.argv if a != "--nglyph"]
        print("[+] Running in --nglyph mode: will only generate .nglyph files (no audio conversion, no GlyphModder).")

    # accept --csv flag: only produce .csv files with zone brightnesses, skip conversion and GlyphModder
    csv_only = False
    if "--csv" in sys.argv:
        csv_only = True
        sys.argv = [a for a in sys.argv if a != "--csv"]
        print("[+] Running in --csv mode: will only generate .csv files with zone brightnesses (no audio conversion, no GlyphModder).")

    # accept --compact flag: only produce .musicviz files with zone brightnesses in binary format, skip conversion and GlyphModder
    compact_only = False
    if "--compact" in sys.argv:
        compact_only = True
        sys.argv = [a for a in sys.argv if a != "--compact"]
        print("[+] Running in --compact mode: will only generate .musicviz files with zone brightnesses in binary format (no audio conversion, no GlyphModder).")

    # determine selected phone config
    selected_phone_key = None  # default to None, will choose later
    user_specified = False
    # look for any CLI args beginning with "--np" (last one wins)
    # ignore the script name at sys.argv[0]
    cli_args = sys.argv[1:]
    if cli_args:
        np_flags = [a for a in cli_args if isinstance(a, str) and a.startswith("--np")]
        if np_flags:
            # map "--np1s" -> "np1s"
            selected_phone_key = np_flags[-1].lstrip("-")
            user_specified = True

    # Attempt to pull GlyphModder.py into cwd if --update was requested
    if update_flag:
        try:
            download_glyphmodder_to_cwd(overwrite=True)
        except Exception as e_download:
            print(f"[!] Warning: automatic GlyphModder fetch failed: {e_download}")
        # continue — run_glyphmodder_write will still search parent dir / cwd as before
    # prepare directories
    input_dir = "Input"
    output_dir = "Output"
    nglyph_dir = "Nglyph"
    cfg_path = "zones.config"

    if not os.path.isdir(input_dir):
        print(f"[+] Creating input directory: {input_dir}")
        os.makedirs(input_dir, exist_ok=True)
        print(f"[!] Please place audio files into the '{input_dir}' folder and re-run. Supported types include mp3, ogg, and m4a.")
        sys.exit(0)

    if not os.path.isdir(output_dir):
        os.makedirs(output_dir, exist_ok=True)

    if not os.path.isdir(nglyph_dir):
        os.makedirs(nglyph_dir, exist_ok=True)

    if not os.path.isfile(cfg_path):
        print("[!] zones.config not found in working directory.")
        sys.exit(1)

    raw_cfg = json.load(open(cfg_path, "r", encoding="utf-8"))

    # require multi-config format (no legacy single-config)
    if "zones" in raw_cfg:
        print("[!] Legacy single-config format is no longer supported. Please convert zones.config to the multi-config format (top-level 'amp' and per-phone entries).")
        sys.exit(1)
    # top-level amp must exist
    global_amp = raw_cfg.get("amp")
    if global_amp is None:
        print("[!] zones.config must include a top-level 'amp' object. Please add it.")
        sys.exit(1)

    # read optional global decay value (accept both 'decay-alpha' and 'decay_alpha')
    raw_global_decay = None
    if "decay-alpha" in raw_cfg:
        raw_global_decay = raw_cfg.get("decay-alpha")
    elif "decay_alpha" in raw_cfg:
        raw_global_decay = raw_cfg.get("decay_alpha")
    global_decay = None
    if raw_global_decay is not None:
        try:
            global_decay = float(raw_global_decay)
        except Exception:
            print("[!] Invalid decay value in zones.config; 'decay-alpha' must be numeric.")
            sys.exit(1)
    
    conf_map = {k: v for k, v in raw_cfg.items() if k != "amp"}

    # choose selected config (default to np1 if present, else first key)
    if selected_phone_key is None:
        selected_phone_key = "np1" if "np1" in conf_map else next(iter(conf_map.keys()))

    conf = conf_map.get(selected_phone_key)
    if conf is None:
        if user_specified:
            print(f"[!] Requested config '{selected_phone_key}' not found in zones.config. Run the script without arguments to see available configs.")
            sys.exit(1)
        else:
            print(f"[!] Default config '{selected_phone_key}' not found in zones.config. Falling back to first available config.")
            conf = conf_map[next(iter(conf_map.keys()))]

    # use per-phone amp if present, otherwise use global amp (do NOT create defaults)
    amp_conf = conf.get("amp") if conf.get("amp") is not None else global_amp
    if amp_conf is None:
        print("[!] Missing 'amp' configuration. Please add a top-level 'amp' in zones.config or an 'amp' object in the selected phone config.")
        sys.exit(1)

    try:
        amp_conf = validate_amp_conf(amp_conf)
    except ValueError as e:
        print(f"[!] Invalid 'amp' configuration: {e}")
        sys.exit(1)

    # inject validated amp back into conf so downstream code reads numeric values
    conf["amp"] = amp_conf

    # validate selected phone config: require 'zones' list and numeric 'decay_alpha'
    if "zones" not in conf or not isinstance(conf["zones"], list):
        print("[!] Selected config missing 'zones' array. Please add a 'zones' list to the phone config in zones.config.")
        sys.exit(1)
    
    # decay-alpha may be present per-phone or provided globally
    if "decay-alpha" not in conf:
        if global_decay is not None:
            conf["decay_alpha"] = global_decay
        else:
            print("[!] Selected config missing 'decay-alpha' and no global 'decay-alpha' provided. Please add one to zones.config.")
            sys.exit(1)
    else:
        try:
            conf["decay_alpha"] = float(conf["decay-alpha"])
        except Exception:
            print("[!] Invalid 'decay-alpha' value in phone config; it must be numeric.")
            sys.exit(1)
    print("Decay value used: " + str(conf["decay_alpha"]))

    files = sorted(os.listdir(input_dir))
    if not files:
        print(f"No files found in '{input_dir}'. Drop audio files there and run again. Supported types include mp3, ogg, m4a.")
        sys.exit(0)

    processed = 0
    for fname in files:
        in_path = os.path.join(input_dir, fname)
        if not os.path.isfile(in_path):
            continue
        base = os.path.splitext(os.path.basename(fname))[0]
        if compact_only:
            output_format = 'compact'
            ext = '.musicviz'
        elif csv_only:
            output_format = 'csv'
            ext = '.csv'
        else:
            output_format = 'nglyph'
            ext = '.nglyph'
        out_path = os.path.join(nglyph_dir, base + ext)   # save file under Nglyph/
        desired_final_ogg = os.path.abspath(os.path.join(output_dir, base + ".ogg"))
        print(f"[+] Processing '{fname}'...")
        final_ogg = None
        # produce the output file
        output_file = process(in_path, conf, out_path, output_format)
        if not nglyph_only and not csv_only and not compact_only:
            # Convert input audio to OGG in output directory first
            source_ogg = convert_to_ogg(in_path, desired_final_ogg)
            final_ogg = run_glyphmodder_write(output_file, source_ogg, conf.get("title"), cwd=os.path.abspath(output_dir))
            
            # GlyphModder may create _fixed_composed.ogg or _composed.ogg depending on whether audio fix was needed
            # Find the actual composed file and rename it to the clean final name
            output_dir_abs = os.path.abspath(output_dir)
            composed_patterns = [
                os.path.join(output_dir_abs, base + "_fixed_composed.ogg"),
                os.path.join(output_dir_abs, base + "_composed.ogg"),
            ]
            composed_file = None
            for pattern in composed_patterns:
                if os.path.isfile(pattern):
                    composed_file = pattern
                    break
            
            if composed_file:
                # Rename to clean final name
                os.rename(composed_file, desired_final_ogg)
                final_ogg = desired_final_ogg
                print(f"[+] Finished this file!")
                
                # Clean up intermediate files (_fixed.ogg, original converted ogg if different)
                for suffix in ["_fixed.ogg"]:
                    intermediate = os.path.join(output_dir_abs, base + suffix)
                    if os.path.isfile(intermediate) and intermediate != desired_final_ogg:
                        os.remove(intermediate)
                        print(f"[+] Cleaned up {intermediate}")
            else:
                print(f"[!] Warning: Could not find composed output file for {base}")
        print("-/--/---/----/-----/------/-------/--------/---------/----------/")
        processed += 1
    print("-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-/-")
    print(f"Done! Processed {processed} file(s) in total. Find them in the output folder!")
