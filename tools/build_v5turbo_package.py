#!/usr/bin/env python3
"""Build a deployment package (.gsvm) for GPT-SoVITS V5 Turbo NPU Engine."""
import os
import sys
import json
import hashlib
import zipfile
import tempfile
from pathlib import Path

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def build_v5turbo_package(output_path: str, prompt_wav_path: str = None):
    out = Path(output_path).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)

    files_manifest = []
    temp_files = []

    # 1. README
    readme_content = (
        "# GPT-SoVITS V5 Turbo NPU Model Package\n\n"
        "Model Version: V5 Turbo (48kHz Studio Quality)\n"
        "Acceleration: 100% Snapdragon Hexagon HTP NPU Resident Pipeline\n"
        "DiT Context: 1000 frames (Option A Dynamic Tail Zero-Padding)\n"
        "Vocoder: 100 frames chunked BigVGAN/Vocos on Hexagon DSP\n"
    ).encode("utf-8")
    tmp_dir = Path(tempfile.gettempdir())
    readme_path = tmp_dir / "v5_package_readme.md"
    readme_path.write_bytes(readme_content)
    temp_files.append((readme_path, "README.md"))

    # 2. Sample Prompt Audio
    if prompt_wav_path and os.path.exists(prompt_wav_path):
        wav_p = Path(prompt_wav_path)
        temp_files.append((wav_p, "sample_prompt.wav"))
        
        prompt_txt_path = tmp_dir / "v5_sample_prompt.txt"
        prompt_txt_path.write_text("这是一个用于高通NPU加速验证的基准音色样例。", encoding="utf-8")
        temp_files.append((prompt_txt_path, "sample_prompt.txt"))

    # Manifest file entries
    for disk_path, arcname in temp_files:
        files_manifest.append({
            "path": arcname,
            "size": disk_path.stat().st_size,
            "sha256": sha256_file(disk_path)
        })

    manifest = {
        "name": "GPT-SoVITS V5 骁龙 NPU 离线引擎",
        "model_version": "v5turbo",
        "product_version": "5.0-npu-turbo",
        "sample_rate": 48000,
        "format_version": 2,
        "executor": "v5turbo-bridge",
        "entrypoint": "synthesize_utf8_to_pcm16",
        "deployable": True,
        "runtime_options_version": 1,
        "runtime_options": {
            "temperature": True,
            "top_p": True,
            "top_k": True,
            "repetition_penalty": True,
            "speed_factor": True,
            "seed": True
        },
        "reference_input_version": 1,
        "bundle_id": "v5turbo-snapdragon-npu:v1",
        "files": files_manifest
    }

    # Write ZIP ensuring manifest.json is the FIRST entry
    manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")
    
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        # First entry: manifest.json
        zf.writestr("manifest.json", manifest_bytes)
        for disk_path, arcname in temp_files:
            zf.write(disk_path, arcname=arcname)

    print(f"[OK] Built V5 Turbo package: {out} ({out.stat().st_size} bytes)")
    return out

if __name__ == "__main__":
    out_file = "/data/data/com.termux/files/home/GPT-SoViTs-android/deploy/v5turbo-snapdragon-npu.gsvm"
    build_v5turbo_package(out_file, prompt_wav_path=None)
