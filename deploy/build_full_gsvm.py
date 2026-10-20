import hashlib
import json
from pathlib import Path
import zipfile

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()

def build_package():
    workspace = Path("/data/data/com.termux/files/home/gpt-sovits-v5-lab")
    deploy_dir = Path("/data/data/com.termux/files/home/GPT-SoViTs-android/deploy")
    deploy_dir.mkdir(parents=True, exist_ok=True)
    
    dit_src = workspace / "onnx_export/v5turbo_cfm/v5turbo_dit_step_f1000_maskfree_gpu.dlc"
    vocoder_src = workspace / "onnx_export/v5turbo_vocoder/v5turbo_vocoder_f100_qnn.dlc"
    
    assert dit_src.exists(), f"Missing {dit_src}"
    assert vocoder_src.exists(), f"Missing {vocoder_src}"
    
    print(f"Hashing DiT DLC: {dit_src.name} ({dit_src.stat().st_size / 1e6:.1f} MB)...", flush=True)
    dit_sha = sha256_file(dit_src)
    dit_size = dit_src.stat().st_size
    
    print(f"Hashing Vocoder DLC: {vocoder_src.name} ({vocoder_src.stat().st_size / 1e6:.1f} MB)...", flush=True)
    vocoder_sha = sha256_file(vocoder_src)
    vocoder_size = vocoder_src.stat().st_size
    
    backend_descriptor = {
        "format": "gsv-qnn-executor",
        "format_version": 1,
        "runtime_abi_version": 1,
        "operation": "synthesize_utf8_to_pcm16",
        "complete": True,
        "utf8_text_input": True,
        "pcm16_output": True,
        "cpu_neural_fallback": False,
        "engine": "gpt-sovits-v5-turbo-qnn",
        "dit_dlc": "runtime/dit.dlc",
        "vocoder_dlc": "runtime/vocoder.dlc"
    }
    desc_bytes = json.dumps(backend_descriptor, indent=2).encode('utf-8')
    desc_sha = hashlib.sha256(desc_bytes).hexdigest()
    desc_size = len(desc_bytes)
    
    readme_text = "GPT-SoVITS V5 骁龙物理 NPU 原生全量自包含模型包 (100% DSP In-Process Resident)\n".encode('utf-8')
    readme_sha = hashlib.sha256(readme_text).hexdigest()
    readme_size = len(readme_text)
    
    files_manifest = [
        {"path": "README.md", "size": readme_size, "sha256": readme_sha},
        {"path": "runtime/backend_descriptor.json", "size": desc_size, "sha256": desc_sha},
        {"path": "runtime/dit.dlc", "size": dit_size, "sha256": dit_sha},
        {"path": "runtime/vocoder.dlc", "size": vocoder_size, "sha256": vocoder_sha}
    ]
    
    manifest = {
        "name": "GPT-SoVITS V5 骁龙 NPU 原生引擎 (全量独立包)",
        "model_version": "v5turbo",
        "product_version": "5.0-npu-native-full",
        "sample_rate": 48000,
        "format_version": 2,
        "executor": "v5turbo-native-htp",
        "entrypoint": "synthesize_utf8_to_pcm16",
        "deployable": True,
        "runtime_options_version": 1,
        "runtime_options": {
            "temperature": True,
            "top_p": True,
            "top_k": True,
            "repetition_penalty": True,
            "speed_factor": True,
            "seed": True,
            "sample_steps": True,
            "fade_window": True,
            "dynamic_padding": True
        },
        "reference_input_version": 1,
        "bundle_id": "v5turbo-snapdragon-native-full:v1",
        "backend_artifact": "runtime/backend_descriptor.json",
        "target_soc": "snapdragon_8_plus_gen_1",
        "target_soc_family": "qualcomm_snapdragon_8",
        "target_asic": "SM8475",
        "target_soc_model": 43,
        "htp_arch": "V69",
        "supported_target_socs": [
            "snapdragon_8_plus_gen_1"
        ],
        "qairt_version": "2.48.0.0",
        "strict_cpu_fallback": False,
        "files": files_manifest
    }
    
    manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode('utf-8')
    
    output_gsvm = deploy_dir / "v5turbo-native-full.gsvm"
    print(f"Creating standalone GSVM package: {output_gsvm} ...", flush=True)
    
    # Crucial: manifest.json MUST be the first zip entry!
    with zipfile.ZipFile(output_gsvm, mode="w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("manifest.json", manifest_bytes)
        zf.writestr("README.md", readme_text)
        zf.writestr("runtime/backend_descriptor.json", desc_bytes)
        print("Writing runtime/vocoder.dlc to package...", flush=True)
        zf.write(vocoder_src, arcname="runtime/vocoder.dlc")
        print("Writing runtime/dit.dlc to package...", flush=True)
        zf.write(dit_src, arcname="runtime/dit.dlc")
        
    print(f"Package successfully created: {output_gsvm.stat().st_size / (1024*1024):.2f} MB", flush=True)

if __name__ == "__main__":
    build_package()
