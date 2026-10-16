"""Recover the existing, approved artwork from repository Base64 chunks unchanged."""
import base64
import hashlib
from pathlib import Path

SPECS = {
    'media_wahid_tv.jpg': ('logo.part*.b64', 5, '0e39bb68c188af300ffdceed84e4d7cec262612108020131de1601003e5e98e4'),
    'mosque.jpg': ('masjid_final.part*.b64', 7, '667ad7deacf950ac42f3cb8bb3b3b80228399b70f15ae7b9f7f5177bfc3adbb5'),
}

def artwork():
    result = {}
    root = Path(__file__).resolve().parents[1] / 'logo-source'
    for name, (pattern, count, digest) in SPECS.items():
        parts = sorted(root.glob(pattern))
        assert len(parts) == count, f'Missing source artwork chunks: {name}'
        data = base64.b64decode(''.join(p.read_text().strip() for p in parts), validate=True)
        assert hashlib.sha256(data).hexdigest() == digest, f'Artwork checksum mismatch: {name}'
        assert data.startswith(b'\xff\xd8\xff') and data.endswith(b'\xff\xd9'), f'Incomplete JPEG: {name}'
        result['assets/watermarks/' + name] = data
    return result
